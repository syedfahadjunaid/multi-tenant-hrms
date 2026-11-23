"""Finding the passages that answer a question: one code path for the service and the eval."""

from collections.abc import Iterable, Sequence
from dataclasses import replace

from app.rag.embeddings import Embedder, default_min_score
from app.rag.rerank import Reranker, default_rerank_min_score
from app.rag.store import ScoredChunk, VectorStore

RRF_K = 60  # the usual constant; larger values flatten the difference between ranks


def reciprocal_rank_fusion(
    rankings: Sequence[Sequence[ScoredChunk]], k: int = RRF_K
) -> list[ScoredChunk]:
    """
    Merges ranked lists by position, not by score: each list gives a chunk 1 / (k + rank).
    That's why it can combine scores that aren't comparable (cosine similarity vs. Postgres
    ts_rank). Chunks keep their ``vector_score``; ``score`` becomes that similarity again, so
    callers still see a meaningful number.
    """
    fused: dict[tuple, float] = {}
    chunks: dict[tuple, ScoredChunk] = {}
    for ranking in rankings:
        for rank, chunk in enumerate(ranking, start=1):
            key = (chunk.document_id, chunk.content)
            fused[key] = fused.get(key, 0.0) + 1.0 / (k + rank)
            chunks.setdefault(key, chunk)
    ordered = sorted(fused, key=fused.__getitem__, reverse=True)
    return [replace(chunks[key], score=chunks[key].vector_score or 0.0) for key in ordered]


class Retriever:
    """
    Stage 1: candidates from vector search, plus keyword search when ``hybrid`` is on,
    merged with reciprocal rank fusion. (Both are permission-filtered inside the store.)
    Stage 2 (optional): a cross-encoder re-orders the candidates.

    Two separate questions are answered:

    * Which passages, in which order? Fusion and/or the reranker if enabled, else the embedding.
    * Is anything relevant at all, or should we say "not found"? ``relevance()`` vs.
      ``min_score``. Keyword ranks and most reranker scores aren't calibrated for that, so
      unless a calibrated reranker decides, it's the best embedding similarity among the
      candidates (the "vector gate"): keywords can change the order, but they can never make
      an unrelated question look answerable.
    """

    def __init__(
        self,
        store: VectorStore,
        embedder: Embedder,
        reranker: Reranker | None = None,
        candidates: int = 20,
        min_score: float | None = None,
        hybrid: bool = False,
    ) -> None:
        self.store = store
        self.embedder = embedder
        self.reranker = reranker
        self.candidates = candidates
        self.hybrid = hybrid

        rerank_cut_off = default_rerank_min_score(reranker) if reranker else None
        reranker_decides = reranker is not None and rerank_cut_off is not None
        self.gate_on_vector = (hybrid or reranker is not None) and not reranker_decides
        if min_score is not None:
            self.min_score = min_score
        elif reranker_decides:
            self.min_score = rerank_cut_off
        else:
            self.min_score = default_min_score(embedder)

    @property
    def name(self) -> str:
        name = self.embedder.name + (" + keywords (RRF)" if self.hybrid else "")
        if self.reranker is not None:
            gate = "embedding" if self.gate_on_vector else "reranker"
            name += f" + {self.reranker.name} ({gate} decides 'not found')"
        return name

    def rank(
        self, tenant_id: str, roles: Iterable[str], question: str, top_k: int
    ) -> list[ScoredChunk]:
        """Best-first, without applying the cut-off (the eval needs the scores)."""
        query = self.embedder.embed_query(question)
        if not query.any():  # no searchable words at all
            return []
        if self.reranker is None and not self.hybrid:
            return self.store.search(tenant_id, roles, query, top_k)

        n = max(top_k, self.candidates)
        candidates = [
            replace(c, vector_score=c.score) for c in self.store.search(tenant_id, roles, query, n)
        ]
        if self.hybrid:
            keyword = self.store.keyword_search(tenant_id, roles, question, query, n)
            candidates = reciprocal_rank_fusion([candidates, keyword])[:n]
        if self.reranker is not None:
            scores = self.reranker.score(question, [c.content for c in candidates])
            candidates = [replace(c, score=s) for c, s in zip(candidates, scores, strict=True)]
            candidates.sort(key=lambda c: c.score, reverse=True)
        return candidates[:top_k]

    def relevance(self, hits: list[ScoredChunk]) -> float:
        """How sure we are that the question is answerable at all (compared with min_score)."""
        if not hits:
            return 0.0
        if self.gate_on_vector:
            return max(h.vector_score if h.vector_score is not None else h.score for h in hits)
        return hits[0].score

    def retrieve(
        self, tenant_id: str, roles: Iterable[str], question: str, top_k: int
    ) -> list[ScoredChunk]:
        """The chunks good enough to answer from. Empty means "not in your documents"."""
        hits = self.rank(tenant_id, roles, question, top_k)
        if self.gate_on_vector:
            # The embedding says whether anything is relevant; the fused/reranked order stands.
            return hits if self.relevance(hits) >= self.min_score else []
        return [h for h in hits if h.score >= self.min_score]
