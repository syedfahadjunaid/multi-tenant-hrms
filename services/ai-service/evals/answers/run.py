"""Answer-quality evaluation: are the assistant's answers right, grounded and cited?

The retrieval eval asks "did we find the right policy?". This one asks what the employee
actually gets: it runs the real answer path (AssistantService.ask: retrieval, PII redaction,
the prompt-injection-safe prompt, Claude) over the HR corpus and grades every answer:

* correct (headline): if the right policy reached Claude, the answer gives what that policy
  says; if it didn't (no policy covers the question, or retrieval missed it), the answer says
  the documents don't cover it instead of guessing. LLM judge, reading the right policy.
* grounded: every policy statement is supported by the documents Claude was shown. LLM judge.
* cited: answers cite a document as [n], and every [n] was shown. Programmatic, free.

Each case is tagged with what happened before the model was called, so a failure can be
blamed on the right stage: "answerable" (right policy in the context), "retrieval-miss"
(answerable, but the right policy wasn't retrieved), "unanswerable" (no policy covers it, yet
related policies passed the cut-off) and "no-llm-call" (nothing passed the cut-off, so the
service said "not found" without calling Claude; graded without a judge).

Results go to <repo>/.claude/hillclimb/answer-quality/<variant>/ (results.jsonl, traces/,
judge/, errors.jsonl), one row per (question, rep), written as each case finishes, so an
interrupted run resumes where it stopped. Calls that fail (API errors after retries,
timeouts, judge failures) go to errors.jsonl and are retried on the next run; they are never
scored as wrong answers.

Usage (from services/ai-service; needs ANTHROPIC_API_KEY in ../../.env):
    python -m evals.answers.run --limit 5               # pilot: the first 5 questions
    python -m evals.answers.run                         # all 189 questions of hr_large.json
    python -m evals.answers.run --answerer oracle       # harness check: should score ~100%
    python -m evals.answers.run --answerer empty        # harness check: should score ~0%
    python -m evals.answers.run --variant v1 --effort low   # a variant, compared with baseline
    python -m evals.answers.run --summary               # reprint the summary from saved rows
The first real run stops until you review the harness and pass --approve-harness once.
"""

import argparse
import hashlib
import html
import json
import logging
import random
import re
import sys
import threading
import time
from collections.abc import Callable, Iterable, Sequence
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import anthropic
import numpy as np

from app.config import Settings
from app.llm import LLMClient, LLMResult, LLMUnavailableError
from app.main import build_embedder, build_llm, build_service
from app.rag.embeddings import Embedder
from app.rag.service import AssistantService
from app.security import Principal
from evals.answers.judge import (
    Expected,
    GraderError,
    Judge,
    Reference,
    check_citations,
    served_model_matches,
)
from evals.retrieval.run import (
    DATASETS,
    ROLES,
    TENANT,
    acceptable_titles,
    bootstrap_ci,
    build_store,
    store_factory,
)

AI_SERVICE_DIR = Path(__file__).resolve().parents[2]
FLOWS_DIR = AI_SERVICE_DIR.parents[1] / ".claude" / "hillclimb"
FLOW = "answer-quality"
VARIANT_NAME = re.compile(r"^(baseline|v\d+)$")
PRINCIPAL = Principal(subject="answer-eval", tenant_id=TENANT, roles=frozenset(ROLES))

# Files whose change means "the eval itself changed". Relative to services/ai-service.
HARNESS_PATHS = [
    "evals/answers/run.py",
    "evals/answers/judge.py",
    "evals/retrieval/run.py",
    "evals/retrieval/datasets/hr_large.json",
    "pyproject.toml",
]
METRICS = [  # the first binary metric is the report's headline
    {"id": "correct", "label": "correct", "kind": "binary"},
    {"id": "grounded", "label": "grounded", "kind": "binary"},
    {"id": "cited", "label": "cited", "kind": "binary"},
]
PERF_FIELDS = [
    {"id": "latency_s", "label": "latency", "unit": "s"},
    {"id": "cost_usd", "label": "cost", "unit": "$"},
    {"id": "in_tokens", "label": "in tok"},
    {"id": "out_tokens", "label": "out tok"},
    {"id": "answer_words", "label": "words"},
    {"id": "refused", "label": "refused"},
    {"id": "fallback", "label": "fallback"},
]
# US$ per million tokens (first-party API). The report derives cost from these and each
# row's served model and usage; update them here when prices change.
PRICES = {
    "claude-opus-5-5": {"in": 4.0, "out": 20.0, "cache_read": 0.20, "cache_write": 5.0},
    "claude-sonnet-5-5": {"in": 2.0, "out": 10.0, "cache_read": 0.10, "cache_write": 2.50},
    "claude-haiku-5-5": {"in": 0.10, "out": 0.50, "cache_read": 0.01, "cache_write": 0.125},
}
EMPTY_USAGE = {
    "input_tokens": 0,
    "output_tokens": 0,
    "cache_read_input_tokens": 0,
    "cache_creation_input_tokens": 0,
}


# --- cases -------------------------------------------------------------------------------


@dataclass(frozen=True)
class Case:
    id: str
    question: str
    acceptable: frozenset[str]
    slice: str


def case_id(question: str) -> str:
    """Readable and path-safe, stable when questions are reordered."""
    slug = re.sub(r"[^a-z0-9]+", "-", question.lower()).strip("-")[:48].rstrip("-")
    return f"{slug}-{hashlib.sha256(question.encode()).hexdigest()[:6]}"


def load_cases(data: dict) -> list[Case]:
    return [
        Case(
            case_id(q["question"]),
            q["question"],
            frozenset(acceptable_titles(q)),
            q.get("slice", "general"),
        )
        for q in data["questions"]
    ]


def case_kind(acceptable: frozenset[str], shown: Iterable[str]) -> str:
    shown = set(shown)
    if not shown:
        return "no-llm-call"
    if acceptable & shown:
        return "answerable"
    return "retrieval-miss" if acceptable else "unanswerable"


def reference_for(case: Case, shown: Iterable[str], policies: dict[str, str]) -> Reference | None:
    """The right policy, preferring one that was shown when several are acceptable."""
    if not case.acceptable:
        return None
    shown_right = sorted(case.acceptable & set(shown))
    title = (shown_right or sorted(case.acceptable))[0]
    return Reference(title, policies[title])


# --- the system under test ---------------------------------------------------------------


_DOCUMENT = re.compile(r'<document index="(\d+)" title="([^"]*)">\n(.*?)\n</document>', re.S)


def shown_documents(assistant_input: str) -> list[tuple[int, str, str]]:
    """(index, title, content) of each passage in the prompt build_prompt made."""
    return [
        (int(i), html.unescape(title), html.unescape(content))
        for i, title, content in _DOCUMENT.findall(assistant_input)
    ]


class RecordingLLM:
    """Passes calls to the real client and remembers the last one per thread, so the eval
    sees the exact prompt the service built without re-implementing any of it."""

    def __init__(self, inner: LLMClient) -> None:
        self._inner = inner
        self._local = threading.local()

    def complete(self, system: str, user: str) -> LLMResult:
        started = time.perf_counter()
        result = self._inner.complete(system, user)
        self._local.call = (system, user, result, time.perf_counter() - started)
        return result

    def take(self) -> tuple[str, str, LLMResult, float] | None:
        call = getattr(self._local, "call", None)
        self._local.call = None
        return call


class LockedEmbedder:
    """The eval answers several questions at once; one query embedding at a time is plenty."""

    def __init__(self, inner: Embedder) -> None:
        self._inner = inner
        self._lock = threading.Lock()
        self.name = inner.name
        self.dim = inner.dim

    def embed_documents(self, texts: Sequence[str]) -> list[np.ndarray]:
        with self._lock:
            return self._inner.embed_documents(texts)

    def embed_query(self, text: str) -> np.ndarray:
        with self._lock:
            return self._inner.embed_query(text)


class OracleAnswerer:
    """Known-good answers for checking the harness: the right policy's own text, cited, or a
    plain "not found" when the right policy wasn't shown. Should score ~100% everywhere."""

    name = "oracle"

    def __init__(self, cases: list[Case]) -> None:
        self._acceptable = {c.question: c.acceptable for c in cases}

    def complete(self, system: str, user: str) -> LLMResult:
        question = user.rsplit("\nQuestion: ", 1)[-1]
        for index, title, content in shown_documents(user):
            if title in self._acceptable.get(question, ()):
                return LLMResult(f"{content} [{index}]", self.name, "end_turn", EMPTY_USAGE)
        return LLMResult(
            "The documents I have don't cover this. Please contact HR.",
            self.name,
            "end_turn",
            EMPTY_USAGE,
        )


class EmptyAnswerer:
    """Known-bad: says nothing. Should score ~0% on correct."""

    name = "empty"

    def complete(self, system: str, user: str) -> LLMResult:
        return LLMResult("", self.name, "end_turn", EMPTY_USAGE)


class WrongAnswerer:
    """Known-bad: confidently answers with a shown policy that does not answer the question.
    Should fail correct but pass grounded and cited, which checks the judges are independent."""

    name = "wrong"

    def __init__(self, cases: list[Case]) -> None:
        self._acceptable = {c.question: c.acceptable for c in cases}

    def complete(self, system: str, user: str) -> LLMResult:
        question = user.rsplit("\nQuestion: ", 1)[-1]
        wrong = [
            (i, t, c)
            for i, t, c in shown_documents(user)
            if t not in self._acceptable.get(question, ())
        ]
        if not wrong:
            return LLMResult("Yes, that's allowed.", self.name, "end_turn", EMPTY_USAGE)
        index, _, content = wrong[0]
        return LLMResult(
            f"Here's the rule: {content} [{index}]", self.name, "end_turn", EMPTY_USAGE
        )


# --- running one case --------------------------------------------------------------------


class CaseTimeoutError(Exception):
    failure_class = "timeout"


def is_transient(error: BaseException) -> bool:
    cause = error.__cause__ if isinstance(error, LLMUnavailableError) else error
    if isinstance(cause, anthropic.APIConnectionError):  # includes timeouts
        return True
    if isinstance(cause, anthropic.APIStatusError):
        return cause.status_code == 429 or cause.status_code >= 500
    return False


def with_backoff(
    call: Callable[[], Any], attempts: int = 5, base_s: float = 2.0
) -> tuple[Any, int]:
    """Retry rate limits and server errors with full-jitter exponential backoff. Returns
    (result, retries); on giving up, the error carries ``retries`` so it lands in the data."""
    retries = 0
    while True:
        try:
            return call(), retries
        except Exception as e:
            if not is_transient(e) or retries + 1 >= attempts:
                e.retries = retries  # type: ignore[attr-defined]
                raise
            retries += 1
            # Jitter for retries, not cryptography.
            time.sleep(random.uniform(0, min(60.0, base_s * 2**retries)))  # noqa: S311


def with_ceiling(work: Callable[[], Any], seconds: float) -> Any:
    """A hard wall-clock limit per case. A hung call keeps running in its (daemon) thread,
    but the case is recorded as a timeout and the worker moves on."""
    box: dict[str, Any] = {}

    def target() -> None:
        try:
            box["value"] = work()
        except BaseException as e:  # handed to the caller below
            box["error"] = e

    thread = threading.Thread(target=target, daemon=True)
    thread.start()
    thread.join(seconds)
    if thread.is_alive():
        raise CaseTimeoutError(f"no result after {seconds:.0f} s")
    if "error" in box:
        raise box["error"]
    return box["value"]


@dataclass
class Context:
    service: AssistantService
    recorder: RecordingLLM
    judge: Judge
    policies: dict[str, str]
    requested_model: str
    real_model: bool  # False for the oracle/empty/wrong answerers (no usage to check)
    variant_dir: Path
    timeout_s: float = 300.0
    lock: threading.Lock = field(default_factory=threading.Lock)


@dataclass
class Outcome:
    row: dict[str, Any]
    trace: list[dict[str, Any]]
    judge_exchanges: list[dict[str, Any]]


def evaluate_case(ctx: Context, case: Case, rep: int) -> Outcome:
    def ask() -> tuple[Any, Any]:
        response = ctx.service.ask(PRINCIPAL, case.question)
        return response, ctx.recorder.take()

    (response, call), retries = with_backoff(ask)
    shown = [s.title for s in response.sources]
    kind = case_kind(case.acceptable, shown)
    expected: Expected = "answer" if kind == "answerable" else "abstain"
    row: dict[str, Any] = {
        "prompt_id": case.id,
        "rep": rep,
        "prompt": case.question,
        "tags": [kind, case.slice],
        "meta": {
            "expected_behaviour": expected,
            "right_policy": sorted(case.acceptable),
            "shown_policies": shown,
            **({"retries": retries} if retries else {}),
        },
    }

    if call is None:
        # Nothing passed the relevance cut-off: the service answered without calling Claude.
        right = not case.acceptable
        row |= {
            "model": "none",
            "usage": EMPTY_USAGE,
            "stop_reason": None,
            "status": "ok",
            "latency_s": 0.0,
            "in_tokens": 0,
            "out_tokens": 0,
            "answer_words": len(response.answer.split()),
            "refused": 0,
            "fallback": 0,
            "grade": {"correct": float(right), "grounded": 1.0, "cited": 1.0},
            "explanation": {
                "correct": "No passage passed the relevance cut-off, so the service replied "
                "'not found' without calling the model"
                + ("." if right else ", although a policy answers this (a retrieval miss)."),
                "grounded": "Makes no policy claims.",
                "cited": "No documents were shown.",
            },
        }
        trace = [
            {"role": "user", "content": case.question},
            {"role": "assistant", "content": response.answer},
        ]
        return Outcome(row, trace, [])

    system, user, result, latency_s = call
    if ctx.real_model and not (result.usage and result.usage.get("output_tokens")):
        raise RuntimeError(f"no usage recorded for {case.id}: the client didn't report it")
    documents = shown_documents(user)
    reference = reference_for(case, shown, ctx.policies)
    truncated = result.stop_reason == "max_tokens"
    row |= {
        "model": result.model,
        "usage": result.usage or EMPTY_USAGE,
        "stop_reason": result.stop_reason,
        "status": "truncated" if truncated else "ok",
        "latency_s": round(latency_s, 3),
        "in_tokens": (result.usage or {}).get("input_tokens", 0),
        "out_tokens": (result.usage or {}).get("output_tokens", 0),
        "answer_words": len(result.answer.split()),
        "refused": int(result.stop_reason == "refusal"),
        # The service lets the API retry a refused request on a fallback model. That is
        # production behaviour, so it is kept and counted rather than treated as an error.
        "fallback": int(
            ctx.real_model and not served_model_matches(ctx.requested_model, result.model)
        ),
    }
    trace = [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
        {"role": "assistant", "content": result.answer},
    ]
    if truncated:  # counted and shown, but kept out of the means; not worth a judge call
        ok, why = check_citations(result.answer, len(documents), expected)
        row |= {
            "grade": {"correct": 0.0, "grounded": 0.0, "cited": float(ok)},
            "explanation": {"correct": "Truncated at max_tokens: not judged.", "cited": why},
        }
        return Outcome(row, trace, [])

    try:
        grades, judge_retries = ctx.judge.grade(
            user, result.answer, expected, reference, len(documents)
        )
    except GraderError as e:
        e.app_model, e.app_usage = result.model, result.usage  # billed, so keep it
        raise
    if judge_retries:
        row["meta"]["judge_retries"] = judge_retries
    row |= {
        "judge_model": ctx.judge.model,
        "judge_usage": grades.judge_usage,
        "grade": grades.scores,
        "explanation": grades.explanations,
    }
    return Outcome(row, trace, grades.exchanges)


# --- the run -----------------------------------------------------------------------------


def append_jsonl(path: Path, record: dict[str, Any], lock: threading.Lock) -> None:
    with lock, path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:  # a line cut off by a crash; the case re-runs
                continue
    return rows


def run_one(ctx: Context, case: Case, rep: int) -> bool:
    """Runs one (case, rep) and writes its row, or an error record. True if scored."""
    started = time.perf_counter()
    try:
        outcome = with_ceiling(lambda: evaluate_case(ctx, case, rep), ctx.timeout_s)
    except Exception as e:
        failure = getattr(e, "failure_class", None) or (
            "serving_error" if isinstance(e, LLMUnavailableError) else "harness_error"
        )
        append_jsonl(
            ctx.variant_dir / "errors.jsonl",
            {
                "prompt_id": case.id,
                "rep": rep,
                "failure_class": failure,
                "error": str(e),
                "retries": getattr(e, "retries", 0),
                "model": getattr(e, "app_model", None),
                "usage": getattr(e, "app_usage", None),
                "judge_model": ctx.judge.model if isinstance(e, GraderError) else None,
                "judge_usage": getattr(e, "usage", None),
                "latency_s": round(time.perf_counter() - started, 3),
            },
            ctx.lock,
        )
        print(f"  {case.id} rep{rep} FAILED ({failure}): {e}", file=sys.stderr)
        return False

    name = f"{case.id}_rep{rep}.json"
    (ctx.variant_dir / "traces" / name).write_text(
        json.dumps(outcome.trace, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    if outcome.judge_exchanges:
        (ctx.variant_dir / "judge" / name).write_text(
            json.dumps(outcome.judge_exchanges, indent=2, ensure_ascii=False), encoding="utf-8"
        )
    # The row goes last: a row means the case is done, so resume skips it.
    append_jsonl(ctx.variant_dir / "results.jsonl", outcome.row, ctx.lock)
    return True


def run(
    ctx: Context, cases: list[Case], reps: int, concurrency: int, progress: bool = True
) -> tuple[int, int]:
    for sub in ("traces", "judge"):
        (ctx.variant_dir / sub).mkdir(parents=True, exist_ok=True)
    done = {(r["prompt_id"], r["rep"]) for r in read_jsonl(ctx.variant_dir / "results.jsonl")}
    todo = [(c, rep) for rep in range(reps) for c in cases if (c.id, rep) not in done]
    if progress:
        print(f"{len(todo)} to run ({len(done)} already done)", file=sys.stderr)
    ok = failed = 0
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(run_one, ctx, c, rep) for c, rep in todo]
        for i, future in enumerate(as_completed(futures), start=1):
            if future.result():
                ok += 1
            else:
                failed += 1
            if progress and (i % 10 == 0 or i == len(todo)):
                elapsed = time.perf_counter() - started
                print(
                    f"  {i}/{len(todo)} ({ok} ok, {failed} failed), {elapsed:.0f} s",
                    file=sys.stderr,
                )
    return ok, failed


# --- state, harness gate, summary --------------------------------------------------------


def harness_sha(paths: list[str]) -> str:
    digest = hashlib.sha256()
    for rel in sorted(paths):
        digest.update(rel.encode() + b"\0")
        digest.update((AI_SERVICE_DIR / rel).read_bytes())
    return digest.hexdigest()


def load_state(flow_dir: Path) -> dict[str, Any]:
    path = flow_dir / "_state.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    state = {
        "description": "HR policy assistant: answer quality on hr_large.json (see "
        "services/ai-service/evals/answers/run.py)",
        "metrics": METRICS,
        "perf_fields": PERF_FIELDS,
        "prices": PRICES,
        "harness_paths": HARNESS_PATHS,
    }
    flow_dir.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    return state


def check_harness(flow_dir: Path, approve: bool) -> bool:
    """Refuse to spend money with an eval nobody has looked at since it last changed. The
    approval is a person's call: run once with --approve-harness after reviewing the diff."""
    path = flow_dir / "_state.json"
    state = load_state(flow_dir)
    sha = harness_sha(state.get("harness_paths", HARNESS_PATHS))
    if state.get("harness_sha") == sha:
        return True
    if approve:
        state["harness_sha"] = sha
        path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        print(f"harness approved: sha256 {sha[:12]} recorded in {path}", file=sys.stderr)
        return True
    what = "has no approved harness" if "harness_sha" not in state else "harness changed since"
    print(
        f"{path} {what} (now {sha[:12]}). Review evals/answers/ and the dataset, then run "
        "again with --approve-harness.",
        file=sys.stderr,
    )
    return False


def cost_usd(model: str | None, usage: dict[str, int] | None, prices: dict) -> float | None:
    if not usage or not any(usage.values()):
        return 0.0
    price = prices.get(model or "")
    if price is None:
        return None  # unknown model: say so instead of pricing it at zero
    return (
        usage.get("input_tokens", 0) * price["in"]
        + usage.get("output_tokens", 0) * price["out"]
        + usage.get("cache_read_input_tokens", 0) * price.get("cache_read", price["in"] * 0.1)
        + usage.get("cache_creation_input_tokens", 0) * price.get("cache_write", price["in"] * 1.25)
    ) / 1_000_000


def summarize(variant_dir: Path, prices: dict) -> str:
    rows = read_jsonl(variant_dir / "results.jsonl")
    errors = read_jsonl(variant_dir / "errors.jsonl")
    done = {(r["prompt_id"], r["rep"]) for r in rows}
    open_errors = [e for e in errors if (e["prompt_id"], e["rep"]) not in done]
    if not rows:
        return f"{variant_dir}: no results yet ({len(open_errors)} failed attempts)."
    scored = [r for r in rows if r["status"] == "ok"]
    rng = np.random.default_rng(0)
    lines = [
        f"{variant_dir.parent.name}/{variant_dir.name}: {len(rows)} rows "
        f"({len(scored)} scored, {len(rows) - len(scored)} truncated), "
        f"{len(open_errors)} unresolved errors",
    ]
    kinds = ["answerable", "retrieval-miss", "unanswerable", "no-llm-call"]
    for metric in ("correct", "grounded", "cited"):
        flags = np.array([r["grade"][metric] for r in scored], dtype=float)
        low, high = bootstrap_ci(flags, rng)
        by_kind = []
        for kind in kinds:
            sub = [r["grade"][metric] for r in scored if r["tags"][0] == kind]
            if sub:
                by_kind.append(f"{kind} {np.mean(sub):.0%} ({len(sub)})")
        lines.append(
            f"  {metric:<9}{flags.mean():>5.0%}  (95% CI {low:.0%} to {high:.0%})   "
            + " | ".join(by_kind)
        )

    called = [r for r in rows if r["model"] != "none"]
    if called:
        latency = np.array([r["latency_s"] for r in called])
        tokens_in = np.array([r["in_tokens"] for r in called])
        tokens_out = np.array([r["out_tokens"] for r in called])
        models = sorted({r["model"] for r in called})
        refused = sum(r["refused"] for r in rows)
        fallback = sum(r["fallback"] for r in rows)
        lines.append(
            f"  answers: {len(called)} model calls ({', '.join(models)}); "
            f"refused {refused}, fallback {fallback}"
        )
        lines.append(
            f"  latency per answer: median {np.median(latency):.1f} s, "
            f"p90 {np.percentile(latency, 90):.1f} s"
        )
        lines.append(
            f"  tokens per answer: input median {np.median(tokens_in):.0f} "
            f"(range {tokens_in.min()} to {tokens_in.max()}), output median "
            f"{np.median(tokens_out):.0f} (range {tokens_out.min()} to {tokens_out.max()}); "
            f"words median {np.median([r['answer_words'] for r in called]):.0f}"
        )
    answer_cost = [cost_usd(r["model"], r.get("usage"), prices) for r in rows]
    judge_cost = [cost_usd(r.get("judge_model"), r.get("judge_usage"), prices) for r in rows]
    failed_cost = [
        (cost_usd(e.get("model"), e.get("usage"), prices) or 0.0)
        + (cost_usd(e.get("judge_model"), e.get("judge_usage"), prices) or 0.0)
        for e in errors
    ]
    if None in answer_cost or None in judge_cost:
        lines.append("  cost: unknown (a model is missing from 'prices' in _state.json)")
    else:
        total_answer, total_judge = sum(answer_cost), sum(judge_cost)
        per_case = (total_answer + total_judge) / len(rows)
        lines.append(
            f"  cost: answers ${total_answer:.2f} + judge ${total_judge:.2f}"
            f" + failed attempts ${sum(failed_cost):.2f} = "
            f"${total_answer + total_judge + sum(failed_cost):.2f} (${per_case:.4f} per case)"
        )
    return "\n".join(lines)


def build_answerer(name: str, settings: Settings, cases: list[Case]) -> LLMClient:
    if name == "oracle":
        return OracleAnswerer(cases)
    if name == "empty":
        return EmptyAnswerer()
    if name == "wrong":
        return WrongAnswerer(cases)
    llm = build_llm(settings)
    if llm is None:
        raise RuntimeError("no ANTHROPIC_API_KEY")  # main() checks this first
    return llm


def main() -> None:
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
    # The service's per-question audit log line would drown the progress output.
    logging.getLogger("app.rag.service").setLevel(logging.WARNING)
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--variant", default="baseline", help="'baseline' or v1, v2, ...")
    parser.add_argument("--model", help="answer model (default: LLM_MODEL from settings)")
    parser.add_argument("--effort", help="answer effort (default: LLM_EFFORT from settings)")
    parser.add_argument("--judge-model", default="claude-sonnet-5-5")
    parser.add_argument("--judge-effort", default="medium")
    parser.add_argument(
        "--answerer",
        choices=["claude", "oracle", "empty", "wrong"],
        default="claude",
        help="oracle/empty/wrong are known-good and known-bad answers that check the judge",
    )
    parser.add_argument("--dataset", default="large", help="'small', 'large' or a JSON path")
    parser.add_argument("--limit", type=int, help="only the first N questions (a pilot)")
    parser.add_argument("--reps", type=int, default=1)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout-s", type=float, default=300.0, help="ceiling per case")
    parser.add_argument("--store", choices=["memory", "postgres"], default="memory")
    parser.add_argument("--summary", action="store_true", help="only print the saved summary")
    parser.add_argument("--approve-harness", action="store_true")
    args = parser.parse_args()

    if not VARIANT_NAME.match(args.variant):
        parser.error("--variant must be 'baseline' or v<N> (the report ignores other names)")
    flow_dir = FLOWS_DIR / (FLOW if args.answerer == "claude" else f"{FLOW}-{args.answerer}")
    variant_dir = flow_dir / args.variant
    state = load_state(flow_dir)
    if args.summary:
        print(summarize(variant_dir, state.get("prices", PRICES)))
        return

    settings = Settings()
    if not settings.llm_enabled:
        sys.exit(
            "ANTHROPIC_API_KEY is not set (put it in the repo's .env). The answer eval calls "
            "Claude for answers and for grading, so it has no offline mode; the unit tests "
            "(tests/test_answer_eval.py) cover it without a key."
        )
    overrides = {k: v for k, v in {"llm_model": args.model, "llm_effort": args.effort}.items() if v}
    settings = settings.model_copy(update=overrides)
    # One approval covers the eval code, whichever answerer runs.
    if not check_harness(FLOWS_DIR / FLOW, args.approve_harness):
        sys.exit(2)

    path = DATASETS.get(args.dataset, Path(args.dataset))
    data = json.loads(path.read_text(encoding="utf-8"))
    cases = load_cases(data)[: args.limit]
    policies = {d["title"]: d["content"] for d in data["documents"]}
    recorder = RecordingLLM(build_answerer(args.answerer, settings, cases))
    judge_client = anthropic.Anthropic(
        api_key=settings.anthropic_api_key.get_secret_value(),  # type: ignore[union-attr]
        max_retries=0,  # retried by with_backoff, which counts the retries
        timeout=settings.llm_timeout_seconds,
    )
    judge = Judge(judge_client, args.judge_model, args.judge_effort, retry=with_backoff)

    print(
        f"{path.name}: {len(cases)} questions x {args.reps} reps, answers by "
        f"{args.answerer if args.answerer != 'claude' else settings.llm_model} "
        f"(effort {settings.llm_effort}), judged by {args.judge_model} -> {variant_dir}",
        file=sys.stderr,
    )
    started = time.perf_counter()
    with store_factory(args.store) as new_store:
        embedder = LockedEmbedder(build_embedder(settings))
        store = build_store(embedder, data, new_store(embedder))
        ctx = Context(
            service=build_service(settings, store, embedder, recorder),
            recorder=recorder,
            judge=judge,
            policies=policies,
            requested_model=settings.llm_model,
            real_model=args.answerer == "claude",
            variant_dir=variant_dir,
            timeout_s=args.timeout_s,
        )
        _, failed = run(ctx, cases, args.reps, args.concurrency)
    print(f"wall-clock {time.perf_counter() - started:.0f} s", file=sys.stderr)
    print(summarize(variant_dir, state.get("prices", PRICES)))
    if failed:
        print(f"{failed} attempts failed: see {variant_dir / 'errors.jsonl'}; re-run to retry.")


if __name__ == "__main__":
    main()
