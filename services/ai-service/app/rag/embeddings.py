"""Text -> vector embeddings.

Two implementations behind one interface:

* ``FastEmbedEmbedder`` (default): a real embedding model, BAAI/bge-small-en-v1.5, run locally
  with ONNX Runtime on the CPU. It matches meaning, not just words: "vacation" finds
  "annual leave". No API key, no per-call cost, and no document text leaves the server.
* ``HashingEmbedder``: dependency-free word matching. Used in fast unit tests, and kept as a
  baseline in the retrieval evaluation (evals/retrieval) to show what a real model adds.

Each embedder has a ``name`` that is stored with every chunk, so switching models can't mix
vectors from different models in one search (see PgVectorStore.reembed_stale).
"""

import hashlib
import re
from collections.abc import Sequence
from typing import Protocol

import numpy as np

# Calibrated with evals/retrieval/run.py: below this similarity, a chunk is treated as
# "not about the question" and the assistant says it couldn't find an answer. Every model has
# its own score scale (bge-small rarely goes below 0.3 even for unrelated text), so a cut-off
# from one model is meaningless for another. The best cut-off also rises with the size of the
# document collection (more documents, more chances for an unrelated question to half-match
# one), so bge-small's 0.55 is chosen to hold up on both the 9- and the 66-document sets.
DEFAULT_MIN_SCORES: dict[str, float] = {
    "hashing-v1": 0.06,
    "BAAI/bge-small-en-v1.5": 0.55,
    "sentence-transformers/all-MiniLM-L6-v2": 0.35,  # best on the 66-document set
    "snowflake/snowflake-arctic-embed-s": 0.74,  # best on the 66-document set
}


class Embedder(Protocol):
    name: str
    dim: int

    def embed_documents(self, texts: Sequence[str]) -> list[np.ndarray]: ...

    def embed_query(self, text: str) -> np.ndarray: ...


class FastEmbedEmbedder:
    """A sentence-embedding model run locally (model files are downloaded once, then cached)."""

    def __init__(self, model_name: str = "BAAI/bge-small-en-v1.5", cache_dir: str | None = None):
        from fastembed import TextEmbedding  # imported lazily: unit tests don't need it

        supported = {m["model"]: m for m in TextEmbedding.list_supported_models()}
        if model_name not in supported:
            raise ValueError(f"Unsupported embedding model: {model_name}")
        self.name = model_name
        self.dim = int(supported[model_name]["dim"])
        self._model = TextEmbedding(model_name, cache_dir=cache_dir)

    def embed_documents(self, texts: Sequence[str]) -> list[np.ndarray]:
        return [_normalize(v) for v in self._model.passage_embed(list(texts))]

    def embed_query(self, text: str) -> np.ndarray:
        # BGE models embed queries with an instruction prefix; query_embed adds it.
        return _normalize(next(iter(self._model.query_embed(text))))


STOPWORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from",
    "how", "i", "if", "in", "is", "it", "my", "of", "on", "or", "our", "the", "their",
    "this", "to", "was", "what", "when", "where", "which", "who", "will", "with", "you", "your",
})  # fmt: skip


class HashingEmbedder:
    """Feature-hashed word and word-pair counts. Captures shared words, not meaning."""

    name = "hashing-v1"

    def __init__(self, dim: int = 384) -> None:
        self.dim = dim

    def embed_documents(self, texts: Sequence[str]) -> list[np.ndarray]:
        return [self._embed(text) for text in texts]

    def embed_query(self, text: str) -> np.ndarray:
        return self._embed(text)

    def _embed(self, text: str) -> np.ndarray:
        tokens = [t for t in re.findall(r"[a-z0-9]+", text.lower()) if t not in STOPWORDS]
        features = tokens + [f"{a} {b}" for a, b in zip(tokens, tokens[1:], strict=False)]

        vector = np.zeros(self.dim, dtype=np.float32)
        for feature in features:
            digest = hashlib.blake2b(feature.encode(), digest_size=8).digest()
            h = int.from_bytes(digest, "little")
            sign = 1.0 if h >> 63 else -1.0
            vector[h % self.dim] += sign
        return _normalize(vector)


def _normalize(vector: np.ndarray) -> np.ndarray:
    vector = np.asarray(vector, dtype=np.float32)
    norm = float(np.linalg.norm(vector))
    return vector / norm if norm > 0 else vector


def default_min_score(embedder: Embedder) -> float:
    return DEFAULT_MIN_SCORES.get(embedder.name, 0.5)
