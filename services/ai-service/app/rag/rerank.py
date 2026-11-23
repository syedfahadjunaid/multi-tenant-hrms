"""Second-stage ranking with a cross-encoder.

An embedding model encodes the question and each passage *separately* and compares the two
vectors: fast enough to search everything, but it can miss that "lost my work phone" is about
"lost or stolen devices". A cross-encoder reads the question and one passage *together* and
scores how well that passage answers it. Much more accurate, too slow to run on every chunk.
So we do both: vector search picks ~20 candidates, the cross-encoder re-orders them.
"""

from collections.abc import Sequence
from typing import Protocol

import numpy as np

# "Relevant enough" cut-offs on the reranker's own score (sigmoid of its output, 0..1),
# calibrated with evals/retrieval/run.py. None means the reranker's scores can't separate
# relevant from unrelated text, so the Retriever lets it decide the ORDER only, and decides
# "is anything relevant at all?" with the embedding score instead (the "vector gate").
DEFAULT_RERANK_MIN_SCORES: dict[str, float | None] = {
    "jinaai/jina-reranker-v1-tiny-en": 0.33,
    # Trained on short web-search snippets: they score nearly every policy paragraph ~0.00,
    # yet still rank them well.
    "Xenova/ms-marco-MiniLM-L-6-v2": None,
    "Xenova/ms-marco-MiniLM-L-12-v2": None,
}


class Reranker(Protocol):
    name: str

    def score(self, query: str, passages: Sequence[str]) -> list[float]:
        """Relevance of each passage to the query, from 0 (unrelated) to 1."""
        ...


class CrossEncoderReranker:
    def __init__(self, model_name: str, cache_dir: str | None = None) -> None:
        from fastembed.rerank.cross_encoder import TextCrossEncoder  # lazy: tests don't need it

        self.name = model_name
        self._model = TextCrossEncoder(model_name, cache_dir=cache_dir)

    def score(self, query: str, passages: Sequence[str]) -> list[float]:
        if not passages:
            return []
        logits = np.fromiter(self._model.rerank(query, list(passages)), dtype=np.float64)
        # The models output unbounded logits; the sigmoid turns them into a 0..1 score that
        # is easier to threshold and to show to users.
        return (1.0 / (1.0 + np.exp(-logits))).tolist()


def default_rerank_min_score(reranker: Reranker) -> float | None:
    """The calibrated cut-off, or None to gate on the embedding score (also for unknown models)."""
    return DEFAULT_RERANK_MIN_SCORES.get(reranker.name)
