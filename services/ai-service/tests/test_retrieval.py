"""Hybrid retrieval: reciprocal rank fusion and the rule that keywords never decide "not found"."""

from collections.abc import Sequence
from uuid import uuid4

import numpy as np

from app.rag.retrieval import Retriever, reciprocal_rank_fusion
from app.rag.store import Chunk, InMemoryVectorStore, ScoredChunk

ROLES = ["EMPLOYEE"]


def chunk(title: str, vector_score: float) -> ScoredChunk:
    return ScoredChunk(uuid4(), title, f"{title} text", vector_score, vector_score)


def test_rrf_rewards_chunks_that_rank_well_in_both_lists():
    a, b, c = chunk("A", 0.8), chunk("B", 0.7), chunk("C", 0.6)
    vector_ranking = [a, b, c]
    keyword_ranking = [b]  # B is only second by meaning, but the one keyword match

    fused = reciprocal_rank_fusion([vector_ranking, keyword_ranking])

    # B: 1/62 + 1/61 beats A: 1/61; C: 1/63 stays last
    assert [h.title for h in fused] == ["B", "A", "C"]
    assert [h.score for h in fused] == [0.7, 0.8, 0.6]  # score is the similarity again


class FixedEmbedder:
    """Every text gets the same direction per kind: documents ``doc``, questions ``query``."""

    name = "fixed"
    dim = 4

    def __init__(self, doc: Sequence[float], query: Sequence[float]) -> None:
        self.doc = np.array(doc, dtype=np.float32)
        self.query = np.array(query, dtype=np.float32)

    def embed_documents(self, texts):
        return [self.doc for _ in texts]

    def embed_query(self, text):
        return self.query


def store_with(embedder, texts: dict[str, str]) -> InMemoryVectorStore:
    store = InMemoryVectorStore()
    for title, text in texts.items():
        store.add(
            "acme", ROLES, [Chunk(uuid4(), title, 0, text, embedder.embed_documents([text])[0])]
        )
    return store


POLICIES = {
    "Travel": "Book trains and flights through the travel portal.",
    "Remote": "Always connect through the VPN when working outside the office.",
    "Pension": "The company contributes 6% to your pension.",
}


def test_keywords_lift_an_exact_term_match_the_embedding_cannot_separate():
    # The embedding sees every policy as equally similar (a worst case for vectors)
    blind = FixedEmbedder(doc=[1, 0, 0, 0], query=[1, 0, 0, 0])
    store = store_with(blind, POLICIES)
    question = "Do I need the VPN at a cafe?"

    vector_only = Retriever(store, blind).rank("acme", ROLES, question, top_k=3)
    hybrid = Retriever(store, blind, hybrid=True).rank("acme", ROLES, question, top_k=3)

    assert vector_only[0].title == "Travel"  # just the first one stored
    assert hybrid[0].title == "Remote"  # the only policy that mentions the VPN


def test_keywords_never_make_an_unrelated_question_look_answerable():
    # Documents and questions point in different directions: similarity 0 for everything
    orthogonal = FixedEmbedder(doc=[1, 0, 0, 0], query=[0, 1, 0, 0])
    store = store_with(orthogonal, POLICIES)
    retriever = Retriever(store, orthogonal, hybrid=True, min_score=0.5)

    # "VPN" matches a keyword, but the embedding says nothing is relevant
    assert retriever.rank("acme", ROLES, "VPN", top_k=3)[0].title == "Remote"
    assert retriever.retrieve("acme", ROLES, "VPN", top_k=3) == []


def test_keyword_search_respects_tenant_and_roles():
    embedder = FixedEmbedder(doc=[1, 0, 0, 0], query=[1, 0, 0, 0])
    store = InMemoryVectorStore()
    text = "Engineering band E3 pays 90,000 to 120,000."
    store.add("acme", ["HR_ADMIN"], [Chunk(uuid4(), "Salary bands", 0, text, embedder.doc)])
    store.add("globex", ROLES, [Chunk(uuid4(), "Globex bands", 0, text, embedder.doc)])

    assert store.keyword_search("acme", ["EMPLOYEE"], "salary bands E3", embedder.query, 5) == []
    hr = store.keyword_search("acme", ["HR_ADMIN"], "salary bands E3", embedder.query, 5)
    assert [h.title for h in hr] == ["Salary bands"]
