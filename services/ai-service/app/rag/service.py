"""The assistant: ingest documents, answer questions (retrieval-augmented generation)."""

import logging
import time
from uuid import uuid4

from app.llm import LLMClient
from app.models import AskResponse, IngestRequest, IngestResponse, Source
from app.rag.chunking import chunk_text
from app.rag.embeddings import Embedder
from app.rag.prompts import build_prompt
from app.rag.rerank import Reranker
from app.rag.retrieval import Retriever
from app.rag.store import Chunk, ScoredChunk, VectorStore
from app.security import Principal

log = logging.getLogger(__name__)

NOT_FOUND_ANSWER = "I couldn't find this in the HR documents you have access to. Please contact HR."


class AssistantService:
    def __init__(
        self,
        store: VectorStore,
        embedder: Embedder,
        llm: LLMClient | None,
        top_k: int = 5,
        min_score: float | None = None,
        reranker: Reranker | None = None,
        candidates: int = 20,
        hybrid: bool = False,
    ) -> None:
        self._store = store
        self._embedder = embedder
        self._llm = llm  # None = offline mode
        self._top_k = top_k
        # The "relevant enough" cut-off depends on which model produces the final score,
        # so Retriever picks the calibrated default unless one is given.
        self._retriever = Retriever(store, embedder, reranker, candidates, min_score, hybrid)

    def ingest(self, principal: Principal, request: IngestRequest) -> IngestResponse:
        document_id = uuid4()
        pieces = chunk_text(request.content)
        vectors = self._embedder.embed_documents(pieces)
        chunks = [
            Chunk(document_id, request.title, i, text, vec)
            for i, (text, vec) in enumerate(zip(pieces, vectors, strict=True))
        ]
        # Tenant comes from the verified token, never from the request body.
        self._store.add(principal.tenant_id, request.allowed_roles, chunks)
        log.info(
            "ingest tenant=%s subject=%s document=%s chunks=%d",
            principal.tenant_id,
            principal.subject,
            document_id,
            len(chunks),
        )
        return IngestResponse(document_id=document_id, chunks=len(chunks))

    def ask(self, principal: Principal, question: str) -> AskResponse:
        started = time.perf_counter()
        hits = self._retrieve(principal, question)

        used = hits  # the chunks the answer is based on, reported back as sources
        if not hits:
            answer, model, redactions = NOT_FOUND_ANSWER, "none", 0
        elif self._llm is None:
            answer, model, redactions = _offline_answer(hits), "offline", 0
            used = hits[:1]  # offline mode only shows the best passage
        else:
            prompt = build_prompt(question, hits)
            result = self._llm.complete(prompt.system, prompt.user)
            answer, model, redactions = result.answer, result.model, prompt.redactions

        # Audit log: who asked, which documents were used. Never the question text itself (PII).
        log.info(
            "ask tenant=%s subject=%s hits=%d documents=%s redactions=%d model=%s ms=%d",
            principal.tenant_id,
            principal.subject,
            len(hits),
            sorted({str(h.document_id) for h in hits}),
            redactions,
            model,
            (time.perf_counter() - started) * 1000,
        )
        return AskResponse(answer=answer, sources=_sources(used), model=model)

    def _retrieve(self, principal: Principal, question: str) -> list[ScoredChunk]:
        return self._retriever.retrieve(principal.tenant_id, principal.roles, question, self._top_k)


def _sources(hits: list[ScoredChunk]) -> list[Source]:
    best: dict = {}
    for h in hits:
        if h.document_id not in best or h.score > best[h.document_id].score:
            best[h.document_id] = h
    return [
        Source(document_id=h.document_id, title=h.title, score=round(h.score, 4))
        for h in sorted(best.values(), key=lambda h: h.score, reverse=True)
    ]


def _offline_answer(hits: list[ScoredChunk]) -> str:
    top = hits[0]
    snippet = top.content if len(top.content) <= 500 else top.content[:500] + "..."
    return (
        "Offline mode (no ANTHROPIC_API_KEY configured), so here is the most relevant "
        f"passage instead of a generated answer:\n\n[1] {top.title}\n{snippet}"
    )
