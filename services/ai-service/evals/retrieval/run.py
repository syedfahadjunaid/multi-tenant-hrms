"""Retrieval evaluation: does the assistant find the right policy for a question?

Runs the real pipeline (chunking, embedding, permission-aware search, optional reranking,
all through app.rag.retrieval.Retriever) over an HR corpus and reports per pipeline:

* hit@1 / hit@3: an acceptable document is the top result / in the top three
* MRR: mean reciprocal rank (1.0 = always first, 0.5 = typically second, ...)
* held-out accuracy: end-to-end correctness including "not found", with the "relevant enough"
  cut-off chosen on one half of the questions and scored on the other (2-fold), so the number
  isn't flattered by tuning the threshold on the questions it is scored on
* 95% confidence intervals (bootstrap), and a paired comparison of every pipeline with the
  reference (the first model on its own), so small differences aren't mistaken for real ones
* latency: average time to retrieve for one question on this machine

Usage (from services/ai-service):
    python -m evals.retrieval.run                              # large set: baseline vs bge-small
    python -m evals.retrieval.run --dataset small
    python -m evals.retrieval.run --model BAAI/bge-small-en-v1.5 \\
        --reranker jinaai/jina-reranker-v1-tiny-en --reranker Xenova/ms-marco-MiniLM-L-12-v2
"""

import argparse
import json
import sys
import time
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from app.rag.chunking import chunk_text
from app.rag.embeddings import Embedder, FastEmbedEmbedder, HashingEmbedder
from app.rag.rerank import CrossEncoderReranker
from app.rag.retrieval import Retriever
from app.rag.store import (
    Chunk,
    InMemoryVectorStore,
    PgVectorStore,
    VectorStore,
    configure_connection,
)

DATASETS = {
    "small": Path(__file__).parent / "datasets" / "hr_small.json",
    "large": Path(__file__).parent / "datasets" / "hr_large.json",
}
DEFAULT_MODEL = "BAAI/bge-small-en-v1.5"
TENANT = "eval"
ROLES = ["EMPLOYEE"]
THRESHOLDS = np.round(np.arange(0.0, 1.0, 0.01), 2)
BOOTSTRAP_SAMPLES = 2000


@dataclass
class Result:
    name: str
    min_score: float  # the pipeline's configured cut-off
    latency_ms: float
    ranks: list[int | None]  # rank of the first acceptable document, answerable questions only
    top_scores: np.ndarray  # best score per question
    top_correct: np.ndarray  # is the top document acceptable? (False for unanswerable)
    answerable: np.ndarray
    misses: list[str]
    slices: np.ndarray  # per question: "general", "exact_terms", ...
    question_ranks: list[int | None]  # per question (None for unanswerable or not found)

    @property
    def hit1_flags(self) -> np.ndarray:
        return np.array([r == 1 for r in self.ranks])

    def hit1_in(self, mask: np.ndarray) -> np.ndarray:
        """hit@1 outcomes of the answerable questions selected by ``mask``."""
        return np.array(
            [
                rank == 1
                for rank, answerable, keep in zip(
                    self.question_ranks, self.answerable, mask, strict=True
                )
                if answerable and keep
            ]
        )

    @property
    def hit3(self) -> float:
        return float(np.mean([r is not None and r <= 3 for r in self.ranks]))

    @property
    def mrr(self) -> float:
        return float(np.mean([1 / r if r else 0.0 for r in self.ranks]))

    def ok_flags(self, threshold: float) -> np.ndarray:
        """Per question: right document on top AND confident, or (unanswerable) not confident."""
        confident = self.top_scores >= threshold
        return np.where(self.answerable, self.top_correct & confident, ~confident)

    def best_threshold(self, subset: np.ndarray | None = None) -> float:
        subset = np.ones(len(self.top_scores), dtype=bool) if subset is None else subset
        scores = [self.ok_flags(t)[subset].mean() for t in THRESHOLDS]
        return float(THRESHOLDS[int(np.argmax(scores))])

    def held_out_flags(self) -> np.ndarray:
        """2-fold: pick the cut-off on even-numbered questions, score odd ones, and vice versa."""
        fold = np.arange(len(self.top_scores)) % 2
        flags = np.zeros(len(self.top_scores), dtype=bool)
        for test in (0, 1):
            threshold = self.best_threshold(fold != test)
            flags[fold == test] = self.ok_flags(threshold)[fold == test]
        return flags


def acceptable_titles(item: dict) -> set[str]:
    expected = item["expected"]
    if expected is None:
        return set()
    return {expected} if isinstance(expected, str) else set(expected)


def build_store(embedder: Embedder, data: dict, store: VectorStore | None = None) -> VectorStore:
    store = store or InMemoryVectorStore()
    for doc in data["documents"]:
        pieces = chunk_text(doc["content"])
        vectors = embedder.embed_documents(pieces)
        document_id = uuid.uuid5(uuid.NAMESPACE_URL, f"eval:{doc['title']}")
        store.add(
            TENANT,
            ROLES,
            [
                Chunk(document_id, doc["title"], j, text, vec)
                for j, (text, vec) in enumerate(zip(pieces, vectors, strict=True))
            ],
        )
    return store


@contextmanager
def store_factory(kind: str) -> Iterator[Callable[[Embedder], VectorStore]]:
    """
    "memory": the in-memory store (fast; its keyword search is only a BM25 stand-in).
    "postgres": a throwaway pgvector container and the production PgVectorStore, so the
    eval measures the real SQL: HNSW vector search and Postgres full-text search.
    """
    if kind == "memory":
        yield lambda embedder: InMemoryVectorStore()
        return

    import psycopg
    from psycopg_pool import ConnectionPool

    try:
        from testcontainers.community.postgres import PostgresContainer
    except ImportError:
        from testcontainers.postgres import PostgresContainer

    container = PostgresContainer("pgvector/pgvector:0.8.7-pg18", driver=None)
    container.start()
    try:
        url = container.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            conn.execute("CREATE SCHEMA IF NOT EXISTS ai")
        pool = ConnectionPool(
            url, kwargs={"autocommit": True}, configure=configure_connection, open=True
        )
        try:

            def make(embedder: Embedder) -> VectorStore:
                # Every model's rows share one table; searches only see their own model's rows.
                store = PgVectorStore(pool, embedder)
                store.init_schema()
                return store

            yield make
        finally:
            pool.close()
    finally:
        container.stop()


def evaluate(retriever: Retriever, data: dict) -> Result:
    ranks: list[int | None] = []
    question_ranks: list[int | None] = []
    top_scores, top_correct, answerable, timings, misses = [], [], [], [], []
    for item in data["questions"]:
        started = time.perf_counter()
        hits = retriever.rank(TENANT, ROLES, item["question"], top_k=20)
        timings.append(time.perf_counter() - started)

        titles = list(dict.fromkeys(h.title for h in hits))  # best rank per document
        accept = acceptable_titles(item)
        top_scores.append(retriever.relevance(hits))  # what the "not found" decision uses
        top_correct.append(bool(titles) and titles[0] in accept)
        answerable.append(bool(accept))
        rank = next((i + 1 for i, t in enumerate(titles) if t in accept), None)
        question_ranks.append(rank)
        if accept:
            ranks.append(rank)
            if rank != 1:
                got = titles[0] if titles else "-"
                misses.append(f"{item['question']!r}: got {got!r} (right answer at rank {rank})")

    return Result(
        name=retriever.name,
        min_score=retriever.min_score,
        latency_ms=float(np.mean(timings[1:] or timings) * 1000),  # skip the warm-up call
        ranks=ranks,
        top_scores=np.array(top_scores),
        top_correct=np.array(top_correct),
        answerable=np.array(answerable),
        misses=misses,
        slices=np.array([q.get("slice", "general") for q in data["questions"]]),
        question_ranks=question_ranks,
    )


def report_slices(results: list[Result], rng: np.random.Generator) -> None:
    """The same numbers per kind of question, so an effect on one kind can't hide in the mix."""
    names = sorted(set(results[0].slices))
    if len(names) < 2:
        return
    reference = results[1] if len(results) > 1 else None
    for name in names:
        mask = results[0].slices == name
        n_answerable = int((results[0].answerable & mask).sum())
        print(
            f"\nSlice '{name}': {n_answerable} answerable + {int(mask.sum()) - n_answerable} "
            "unanswerable questions"
        )
        print(
            "| Pipeline | hit@1 (95% CI) | Held-out accuracy (95% CI) "
            "| Change in held-out accuracy vs default (95% CI) | Verdict |"
        )
        print("|---|---|---|---|---|")
        for r in results:
            hit1, held = r.hit1_in(mask), r.held_out_flags()[mask]
            h_lo, h_hi = bootstrap_ci(hit1, rng)
            a_lo, a_hi = bootstrap_ci(held, rng)
            change = verdict_text = ""
            if reference is not None and r is not reference and r is not results[0]:
                d, lo, hi = paired_difference(held, reference.held_out_flags()[mask], rng)
                change, verdict_text = f"{d:+.0%} ({lo:+.0%} to {hi:+.0%})", verdict(lo, hi)
            print(
                f"| {r.name} | {hit1.mean():.0%} ({h_lo:.0%}-{h_hi:.0%}) | "
                f"{held.mean():.0%} ({a_lo:.0%}-{a_hi:.0%}) | {change} | {verdict_text} |"
            )


def bootstrap_ci(flags: np.ndarray, rng: np.random.Generator) -> tuple[float, float]:
    """95% interval for the mean of per-question 0/1 outcomes."""
    idx = rng.integers(0, len(flags), (BOOTSTRAP_SAMPLES, len(flags)))
    means = flags[idx].mean(axis=1)
    return float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def paired_difference(
    a: np.ndarray, b: np.ndarray, rng: np.random.Generator
) -> tuple[float, float, float]:
    """Mean of a - b over the same questions, with a 95% bootstrap interval."""
    diff = a.astype(float) - b.astype(float)
    idx = rng.integers(0, len(diff), (BOOTSTRAP_SAMPLES, len(diff)))
    means = diff[idx].mean(axis=1)
    return float(diff.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


def verdict(low: float, high: float) -> str:
    if low > 0:
        return "better"
    if high < 0:
        return "worse"
    return "no clear difference"


def report(results: list[Result]) -> None:
    rng = np.random.default_rng(42)  # fixed seed: the report is reproducible
    print(
        "| Pipeline | hit@1 (95% CI) | hit@3 | MRR | Held-out accuracy (95% CI) "
        "| Accuracy at configured cut-off | ms/question |"
    )
    print("|---|---|---|---|---|---|---|")
    for r in results:
        h_lo, h_hi = bootstrap_ci(r.hit1_flags, rng)
        held = r.held_out_flags()
        a_lo, a_hi = bootstrap_ci(held, rng)
        print(
            f"| {r.name} | {r.hit1_flags.mean():.0%} ({h_lo:.0%}-{h_hi:.0%}) | {r.hit3:.0%} | "
            f"{r.mrr:.2f} | {held.mean():.0%} ({a_lo:.0%}-{a_hi:.0%}) | "
            f"{r.ok_flags(r.min_score).mean():.0%} (at {r.min_score:.2f}; "
            f"best on all data {r.best_threshold():.2f}) | {r.latency_ms:.0f} |"
        )

    if len(results) > 2:
        reference = results[1]
        print(f"\nCompared with {reference.name} on the same questions (95% CI of the difference):")
        print("| Pipeline | Change in hit@1 | Change in held-out accuracy | Verdict |")
        print("|---|---|---|---|")
        for r in results[2:]:
            d1, lo1, hi1 = paired_difference(r.hit1_flags, reference.hit1_flags, rng)
            d2, lo2, hi2 = paired_difference(r.held_out_flags(), reference.held_out_flags(), rng)
            print(
                f"| {r.name} | {d1:+.0%} ({lo1:+.0%} to {hi1:+.0%}) | "
                f"{d2:+.0%} ({lo2:+.0%} to {hi2:+.0%}) | {verdict(lo2, hi2)} |"
            )

    print("\nTop-result scores (a good cut-off sits between the two groups):")
    for r in results:
        answered = r.top_scores[r.answerable & r.top_correct]
        unanswerable = r.top_scores[~r.answerable]
        print(
            f"  {r.name}: correctly answered min {answered.min():.2f} / median "
            f"{np.median(answered):.2f}; unanswerable median {np.median(unanswerable):.2f} / "
            f"max {unanswerable.max():.2f}"
        )

    report_slices(results, rng)


def main() -> None:
    # Windows consoles default to a legacy code page; never crash on a character it lacks.
    sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--dataset", default="large", help="'small', 'large' or a path to a JSON file"
    )
    parser.add_argument("--model", action="append", help="fastembed embedding model (repeatable)")
    parser.add_argument(
        "--reranker", action="append", help="also try this fastembed cross-encoder (repeatable)"
    )
    parser.add_argument(
        "--hybrid", action="store_true", help="also try keyword + vector search (RRF)"
    )
    parser.add_argument(
        "--store",
        choices=["memory", "postgres"],
        default="memory",
        help="'postgres' runs the production SQL in a throwaway container (needs Docker)",
    )
    parser.add_argument("--cache-dir", default=None)
    parser.add_argument("--show-misses", action="store_true", help="list questions ranked wrong")
    parser.add_argument(
        "--fail-under",
        type=float,
        default=None,
        help="exit with an error if a pipeline's hit@1 is below this (a CI gate), e.g. 0.8",
    )
    args = parser.parse_args()

    path = DATASETS.get(args.dataset, Path(args.dataset))
    data = json.loads(path.read_text(encoding="utf-8"))
    answerable = sum(bool(acceptable_titles(q)) for q in data["questions"])
    print(
        f"{path.name}: {len(data['documents'])} documents, {answerable} answerable + "
        f"{len(data['questions']) - answerable} unanswerable questions ({args.store} store)\n"
    )

    rerankers = [CrossEncoderReranker(r, args.cache_dir) for r in args.reranker or []]
    with store_factory(args.store) as new_store:
        baseline = HashingEmbedder()
        retrievers = [Retriever(build_store(baseline, data, new_store(baseline)), baseline)]
        for model in args.model or [DEFAULT_MODEL]:
            embedder = FastEmbedEmbedder(model, cache_dir=args.cache_dir)
            store = build_store(embedder, data, new_store(embedder))
            for reranker in [None, *rerankers]:
                retrievers.append(Retriever(store, embedder, reranker))
                if args.hybrid:
                    retrievers.append(Retriever(store, embedder, reranker, hybrid=True))
        results = [evaluate(r, data) for r in retrievers]
    report(results)

    if args.show_misses:
        for r in results[1:]:
            print(f"\n{r.name}: not ranked first ({len(r.misses)})")
            for miss in r.misses:
                print(f"  - {miss}")

    if args.fail_under is not None:
        failing = [r for r in results[1:] if r.hit1_flags.mean() < args.fail_under]
        for r in failing:
            print(
                f"\nFAIL: {r.name} hit@1 {r.hit1_flags.mean():.0%} is below {args.fail_under:.0%}"
            )
        if failing:
            sys.exit(1)


if __name__ == "__main__":
    main()
