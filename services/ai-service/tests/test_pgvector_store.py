"""PgVectorStore against a real Postgres + pgvector (Testcontainers), not a mock.

These check the parts that live in SQL: the tenant / role / model filter inside the vector
search, re-embedding after a model change, and the dimension check. Skipped if Docker isn't
available.
"""

from collections.abc import Iterator, Sequence
from uuid import uuid4

import numpy as np
import psycopg
import pytest
from psycopg_pool import ConnectionPool

from app.rag.embeddings import HashingEmbedder
from app.rag.store import (
    Chunk,
    EmbeddingDimensionMismatchError,
    PgVectorStore,
    configure_connection,
)

try:
    from testcontainers.community.postgres import PostgresContainer
except ImportError:  # older testcontainers layout
    from testcontainers.postgres import PostgresContainer

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def pool() -> Iterator[ConnectionPool]:
    container = PostgresContainer("pgvector/pgvector:0.8.7-pg18", driver=None)
    try:
        container.start()
    except Exception as e:  # noqa: BLE001 - any Docker problem means "can't run here"
        pytest.skip(f"Docker is not available: {e}")
    url = container.get_connection_url()
    with psycopg.connect(url, autocommit=True) as conn:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        conn.execute("CREATE SCHEMA IF NOT EXISTS ai")
    pool = ConnectionPool(
        url, kwargs={"autocommit": True}, configure=configure_connection, open=True
    )
    try:
        yield pool
    finally:
        pool.close()
        container.stop()


@pytest.fixture
def store(pool: ConnectionPool) -> PgVectorStore:
    store = PgVectorStore(pool, HashingEmbedder())
    store.init_schema()
    with pool.connection() as conn:
        conn.execute("TRUNCATE ai.document_chunks")
    return store


def add(
    store: PgVectorStore, embedder, tenant: str, roles: list[str], title: str, text: str
) -> None:
    store.add(tenant, roles, [Chunk(uuid4(), title, 0, text, embedder.embed_documents([text])[0])])


def test_search_only_returns_the_callers_tenant_and_roles(store: PgVectorStore) -> None:
    e = HashingEmbedder()
    add(store, e, "acme", ["EMPLOYEE", "HR_ADMIN"], "Leave", "annual leave days per year")
    add(store, e, "acme", ["HR_ADMIN"], "Salaries", "annual salary bands per year")
    add(store, e, "globex", ["EMPLOYEE"], "Globex leave", "annual leave days per year")

    query = e.embed_query("annual leave days")
    employee = store.search("acme", ["EMPLOYEE"], query, top_k=10)
    hr_admin = store.search("acme", ["HR_ADMIN"], query, top_k=10)

    assert [h.title for h in employee] == ["Leave"]
    assert sorted(h.title for h in hr_admin) == ["Leave", "Salaries"]


class RenamedEmbedder:
    """Stands in for "a new embedding model": same dimension, different name and vectors."""

    name = "fake-model-v2"
    dim = 384

    def embed_documents(self, texts: Sequence[str]) -> list[np.ndarray]:
        return [self.embed_query(t) for t in texts]

    def embed_query(self, text: str) -> np.ndarray:
        v = np.zeros(self.dim, dtype=np.float32)
        v[0] = 1.0
        return v


def test_a_new_model_re_embeds_old_chunks_before_searching_them(
    pool: ConnectionPool, store: PgVectorStore
) -> None:
    add(store, HashingEmbedder(), "acme", ["EMPLOYEE"], "Leave", "annual leave days per year")
    new_model = RenamedEmbedder()
    upgraded = PgVectorStore(pool, new_model)
    upgraded.init_schema()

    # Vectors from another model are never compared with the new model's queries
    assert upgraded.search("acme", ["EMPLOYEE"], new_model.embed_query("x"), top_k=5) == []

    assert upgraded.reembed_stale() == 1
    assert [
        h.title for h in upgraded.search("acme", ["EMPLOYEE"], new_model.embed_query("x"), top_k=5)
    ] == ["Leave"]
    assert upgraded.reembed_stale() == 0  # nothing left to do


def test_keyword_search_stems_words_and_keeps_the_permission_filter(store: PgVectorStore) -> None:
    e = HashingEmbedder()
    add(store, e, "acme", ["EMPLOYEE"], "Annual leave", "You get 25 vacation days per year.")
    add(store, e, "acme", ["HR_ADMIN"], "Salary bands", "Band E3 pays 90,000 to 120,000.")
    add(store, e, "globex", ["EMPLOYEE"], "Globex leave", "You get 30 vacation days per year.")

    def search(question: str, roles: list[str]):
        return store.keyword_search("acme", roles, question, e.embed_query(question), 10)

    hits = search("How many vacations do I get?", ["EMPLOYEE"])
    assert [h.title for h in hits] == ["Annual leave"]  # "vacations" matched "vacation"
    assert hits[0].vector_score is not None  # similarity comes along for the fusion step
    assert search("salary bands", ["EMPLOYEE"]) == []  # HR-only document stays hidden
    assert [h.title for h in search("salary bands", ["HR_ADMIN"])] == ["Salary bands"]
    assert search("how do I", ["EMPLOYEE"]) == []  # nothing but stop words


def test_a_model_with_a_different_dimension_is_refused(
    pool: ConnectionPool, store: PgVectorStore
) -> None:
    with pytest.raises(EmbeddingDimensionMismatchError, match="vector\\(384\\)"):
        PgVectorStore(pool, HashingEmbedder(dim=768)).init_schema()
