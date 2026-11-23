"""Build-time step: download the embedding model and reranker into EMBEDDING_CACHE_DIR."""

import os

from fastembed import TextEmbedding
from fastembed.rerank.cross_encoder import TextCrossEncoder

cache_dir = os.environ["EMBEDDING_CACHE_DIR"]
TextEmbedding(os.environ["EMBEDDING_MODEL"], cache_dir=cache_dir)
if os.environ.get("RERANKER_MODEL"):
    TextCrossEncoder(os.environ["RERANKER_MODEL"], cache_dir=cache_dir)
print("models ready in", cache_dir)
