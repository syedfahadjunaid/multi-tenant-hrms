"""Vector storage with permission-aware search.

The access check happens INSIDE the search query (tenant + roles in the WHERE clause), not
by filtering results afterwards. That way a forbidden chunk is never even loaded, and the
top-k results can't be "used up" by documents the user isn't allowed to see.

Every chunk also records which embedding model produced its vector. Vectors from different
models live in different "spaces" and can't be compared, so searches only look at chunks made
by the current model, and ``reembed_stale`` upgrades the rest after a model change.
"""

import logging
import math
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from uuid import UUID

import numpy as np
from pgvector.psycopg import register_vector
from psycopg import Connection
from psycopg_pool import ConnectionPool

from app.rag.embeddings import STOPWORDS, Embedder

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Chunk:
    document_id: UUID
    title: str
    index: int
    content: str
    embedding: np.ndarray


@dataclass(frozen=True)
class ScoredChunk:
    document_id: UUID
    title: str
    content: str
    score: float
    # After reranking, ``score`` is the reranker's; the original similarity is kept here.
    vector_score: float | None = None


def keyword_terms(question: str, limit: int = 32) -> list[str]:
    """Plain words from a question, for keyword search. Letters and digits only, so they're
    always safe to put in a Postgres tsquery (and they're bound as parameters anyway)."""
    return list(dict.fromkeys(re.findall(r"[a-z0-9]+", question.lower())))[:limit]


class VectorStore:
    """Interface. Implementations: PgVectorStore (real) and InMemoryVectorStore (tests)."""

    def add(self, tenant_id: str, allowed_roles: Sequence[str], chunks: Sequence[Chunk]) -> None:
        raise NotImplementedError

    def search(
        self, tenant_id: str, roles: Iterable[str], query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        """Nearest chunks by embedding. ``score`` is the cosine similarity."""
        raise NotImplementedError

    def keyword_search(
        self, tenant_id: str, roles: Iterable[str], question: str, query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        """Chunks sharing words with the question, best first. ``score`` is a keyword rank
        (only its order is meaningful); ``vector_score`` is the chunk's cosine similarity."""
        raise NotImplementedError


@dataclass
class InMemoryVectorStore(VectorStore):
    """For unit tests. Its keyword search is a simple BM25 stand-in; the retrieval eval
    measures the real Postgres full-text search (evals/retrieval/run.py --store postgres)."""

    rows: list[tuple[str, frozenset[str], Chunk]] = field(default_factory=list)

    def add(self, tenant_id: str, allowed_roles: Sequence[str], chunks: Sequence[Chunk]) -> None:
        self.rows.extend((tenant_id, frozenset(allowed_roles), c) for c in chunks)

    def _visible(self, tenant_id: str, roles: Iterable[str]) -> list[Chunk]:
        user_roles = frozenset(roles)
        return [c for t, allowed, c in self.rows if t == tenant_id and allowed & user_roles]

    def search(
        self, tenant_id: str, roles: Iterable[str], query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        hits = [
            ScoredChunk(c.document_id, c.title, c.content, float(np.dot(c.embedding, query)))
            for c in self._visible(tenant_id, roles)
        ]
        return sorted(hits, key=lambda h: h.score, reverse=True)[:top_k]

    def keyword_search(
        self, tenant_id: str, roles: Iterable[str], question: str, query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        terms = [t for t in keyword_terms(question) if t not in STOPWORDS]
        chunks = self._visible(tenant_id, roles)
        docs = [keyword_terms(f"{c.title} {c.content}", limit=10_000) for c in chunks]
        if not terms or not docs:
            return []
        avg_len = sum(map(len, docs)) / len(docs)
        hits = []
        for chunk, words in zip(chunks, docs, strict=True):
            score = 0.0
            for term in terms:
                tf = words.count(term)
                if tf:
                    df = sum(term in d for d in docs)
                    idf = math.log(1 + (len(docs) - df + 0.5) / (df + 0.5))
                    score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * len(words) / avg_len))
            if score > 0:
                cosine = float(np.dot(chunk.embedding, query))
                hits.append(
                    ScoredChunk(chunk.document_id, chunk.title, chunk.content, score, cosine)
                )
        return sorted(hits, key=lambda h: h.score, reverse=True)[:top_k]


def configure_connection(conn: Connection) -> None:
    """Runs on every new pooled connection."""
    register_vector(conn)
    # pgvector 0.8+: keep scanning the HNSW index until enough rows pass the WHERE filter.
    conn.execute("SET hnsw.iterative_scan = relaxed_order")


class EmbeddingDimensionMismatchError(RuntimeError):
    pass


class PgVectorStore(VectorStore):
    def __init__(self, pool: ConnectionPool, embedder: Embedder) -> None:
        self._pool = pool
        self._embedder = embedder
        self._dim = int(embedder.dim)

    def init_schema(self) -> None:
        # Simple idempotent DDL. A bigger service would use a migration tool (e.g. Alembic).
        ddl = f"""
            CREATE TABLE IF NOT EXISTS ai.document_chunks (
                id            BIGSERIAL    PRIMARY KEY,
                tenant_id     TEXT         NOT NULL,
                document_id   UUID         NOT NULL,
                title         TEXT         NOT NULL,
                chunk_index   INT          NOT NULL,
                content       TEXT         NOT NULL,
                allowed_roles TEXT[]       NOT NULL,
                embedding     vector({self._dim}) NOT NULL,
                created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
            );
            -- Added after the first release; older rows were made by the hashing embedder.
            ALTER TABLE ai.document_chunks
                ADD COLUMN IF NOT EXISTS embedding_model TEXT NOT NULL DEFAULT 'hashing-v1';
            DROP INDEX IF EXISTS ai.ix_chunks_tenant;
            CREATE INDEX IF NOT EXISTS ix_chunks_tenant_model
                ON ai.document_chunks (tenant_id, embedding_model);
            CREATE INDEX IF NOT EXISTS ix_chunks_embedding
                ON ai.document_chunks USING hnsw (embedding vector_cosine_ops);
            -- Keyword search: Postgres keeps this English-stemmed word index up to date by
            -- itself ("vacations" matches "vacation"); title words weigh more than body words.
            ALTER TABLE ai.document_chunks ADD COLUMN IF NOT EXISTS content_tsv tsvector
                GENERATED ALWAYS AS (
                    setweight(to_tsvector('english', title), 'A')
                    || setweight(to_tsvector('english', content), 'B')
                ) STORED;
            CREATE INDEX IF NOT EXISTS ix_chunks_fts ON ai.document_chunks USING gin (content_tsv);
        """  # noqa: S608 - the only interpolated value is an int
        with self._pool.connection() as conn:
            conn.execute(ddl)
            # For a vector column, atttypmod holds the dimension it was created with.
            (column_dim,) = conn.execute(
                """
                SELECT atttypmod FROM pg_attribute
                WHERE attrelid = 'ai.document_chunks'::regclass AND attname = 'embedding'
                """
            ).fetchone()
        if column_dim != self._dim:
            raise EmbeddingDimensionMismatchError(
                f"ai.document_chunks.embedding is vector({column_dim}) but {self._embedder.name} "
                f"produces {self._dim} dimensions. Migrate the column (and re-embed) first."
            )

    def add(self, tenant_id: str, allowed_roles: Sequence[str], chunks: Sequence[Chunk]) -> None:
        rows = [
            (
                tenant_id,
                c.document_id,
                c.title,
                c.index,
                c.content,
                list(allowed_roles),
                c.embedding,
                self._embedder.name,
            )
            for c in chunks
        ]
        with self._pool.connection() as conn, conn.transaction(), conn.cursor() as cur:
            cur.executemany(
                """
                INSERT INTO ai.document_chunks (tenant_id, document_id, title, chunk_index,
                                                content, allowed_roles, embedding, embedding_model)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                """,
                rows,
            )

    def search(
        self, tenant_id: str, roles: Iterable[str], query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        # Parameters are always bound (%(name)s), never formatted into the SQL string.
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                SELECT document_id, title, content, 1 - (embedding <=> %(q)s) AS score
                FROM ai.document_chunks
                WHERE tenant_id = %(tenant)s
                  AND embedding_model = %(model)s
                  AND allowed_roles && %(roles)s::text[]
                ORDER BY embedding <=> %(q)s
                LIMIT %(k)s
                """,
                {
                    "q": query,
                    "tenant": tenant_id,
                    "model": self._embedder.name,
                    "roles": sorted(roles),
                    "k": top_k,
                },
            ).fetchall()
        hits = [ScoredChunk(r[0], r[1], r[2], float(r[3])) for r in rows]
        return sorted(hits, key=lambda h: h.score, reverse=True)

    def keyword_search(
        self, tenant_id: str, roles: Iterable[str], question: str, query: np.ndarray, top_k: int
    ) -> list[ScoredChunk]:
        terms = keyword_terms(question)
        if not terms:
            return []
        # Any word may match (OR), stop words are dropped by to_tsquery. Same tenant / model /
        # role filter as the vector search: keyword search must never widen what a user sees.
        with self._pool.connection() as conn:
            rows = conn.execute(
                """
                SELECT document_id, title, content,
                       ts_rank_cd(content_tsv, query, 1) AS score,
                       1 - (embedding <=> %(q)s) AS vector_score
                FROM ai.document_chunks, to_tsquery('english', %(terms)s) AS query
                WHERE tenant_id = %(tenant)s
                  AND embedding_model = %(model)s
                  AND allowed_roles && %(roles)s::text[]
                  AND content_tsv @@ query
                ORDER BY score DESC
                LIMIT %(k)s
                """,
                {
                    "q": query,
                    "terms": " | ".join(terms),
                    "tenant": tenant_id,
                    "model": self._embedder.name,
                    "roles": sorted(roles),
                    "k": top_k,
                },
            ).fetchall()
        return [ScoredChunk(r[0], r[1], r[2], float(r[3]), float(r[4])) for r in rows]

    def reembed_stale(self, batch_size: int = 64) -> int:
        """
        Re-embeds chunks made by a different model, in batches. Safe to run repeatedly and from
        several instances at once: it only ever moves rows to the current model.
        """
        total = 0
        while True:
            with self._pool.connection() as conn:
                rows = conn.execute(
                    """
                    SELECT id, content FROM ai.document_chunks
                    WHERE embedding_model <> %(model)s
                    ORDER BY id LIMIT %(n)s
                    """,
                    {"model": self._embedder.name, "n": batch_size},
                ).fetchall()
                if not rows:
                    break
                vectors = self._embedder.embed_documents([content for _, content in rows])
                with conn.transaction(), conn.cursor() as cur:
                    cur.executemany(
                        "UPDATE ai.document_chunks"
                        " SET embedding = %s, embedding_model = %s WHERE id = %s",
                        [
                            (v, self._embedder.name, row_id)
                            for (row_id, _), v in zip(rows, vectors, strict=True)
                        ],
                    )
            total += len(rows)
        if total:
            log.info("re-embedded %d chunks with %s", total, self._embedder.name)
        return total
