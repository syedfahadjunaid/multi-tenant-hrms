"""FastAPI application: wiring, routes and error handling.

``create_app`` takes optional dependencies so tests can swap in an in-memory store and a
local signing key. That's dependency injection without a framework.
"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request, status
from fastapi.responses import JSONResponse
from psycopg.conninfo import make_conninfo
from psycopg_pool import ConnectionPool

from app.config import Settings
from app.llm import ClaudeClient, LLMClient, LLMUnavailableError
from app.models import AskRequest, AskResponse, IngestRequest, IngestResponse
from app.rag.embeddings import Embedder, FastEmbedEmbedder, HashingEmbedder
from app.rag.rerank import CrossEncoderReranker, Reranker
from app.rag.service import AssistantService
from app.rag.store import PgVectorStore, VectorStore, configure_connection
from app.security import Principal, TokenVerifier, get_principal, jwks_key_resolver, require_role

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ai_service")


def create_app(
    settings: Settings | None = None,
    *,
    service: AssistantService | None = None,
    verifier: TokenVerifier | None = None,
) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        pool: ConnectionPool | None = None
        if service is None:
            embedder = build_embedder(settings)
            pool = _open_pool(settings)
            store = PgVectorStore(pool, embedder)
            store.init_schema()
            # After a model change, older chunks are re-embedded before we serve traffic.
            # Fine for thousands of chunks; a large corpus would do this in a background job.
            store.reembed_stale()
            app.state.service = build_service(settings, store, embedder, build_llm(settings))
            log.info(
                "Hybrid search (keywords + vectors): %s",
                "on" if settings.retrieval_hybrid else "off",
            )
        else:
            app.state.service = service
        app.state.pool = pool
        app.state.verifier = verifier or TokenVerifier(
            settings.jwt_issuer_uri,
            settings.jwt_audience,
            jwks_key_resolver(settings.jwt_jwk_set_uri),
        )
        yield
        if pool is not None:
            pool.close()

    app = FastAPI(
        title="HRMS AI service",
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/docs" if settings.enable_docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.enable_docs else None,
    )

    def get_service(request: Request) -> AssistantService:
        return request.app.state.service

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/health/ready")
    def ready(request: Request) -> JSONResponse:
        pool: ConnectionPool | None = request.app.state.pool
        if pool is not None:
            try:
                with pool.connection(timeout=2) as conn:
                    conn.execute("SELECT 1")
            except Exception:
                log.exception("readiness check failed")
                return JSONResponse({"status": "DOWN"}, status.HTTP_503_SERVICE_UNAVAILABLE)
        return JSONResponse({"status": "UP"})

    @app.post("/v1/documents", status_code=status.HTTP_201_CREATED)
    def ingest(
        body: IngestRequest,
        principal: Principal = Depends(require_role("HR_ADMIN")),  # noqa: B008
        svc: AssistantService = Depends(get_service),  # noqa: B008
    ) -> IngestResponse:
        return svc.ingest(principal, body)

    @app.post("/v1/ask")
    def ask(
        body: AskRequest,
        principal: Principal = Depends(get_principal),  # noqa: B008
        svc: AssistantService = Depends(get_service),  # noqa: B008
    ) -> AskResponse:
        return svc.ask(principal, body.question)

    @app.exception_handler(LLMUnavailableError)
    def llm_unavailable(_: Request, exc: LLMUnavailableError) -> JSONResponse:
        log.warning("LLM unavailable: %s", exc)
        return JSONResponse(
            {"title": "Service Unavailable", "status": 503, "detail": "The assistant is busy."},
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            media_type="application/problem+json",
        )

    return app


def _open_pool(settings: Settings) -> ConnectionPool:
    conninfo = make_conninfo(
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_user,
        password=settings.ai_db_password.get_secret_value(),
        application_name="ai-service",
    )
    pool = ConnectionPool(
        conninfo,
        min_size=1,
        max_size=10,
        kwargs={"autocommit": True},
        configure=configure_connection,
        open=True,
    )
    pool.wait(timeout=30)  # fail fast at startup if the database is unreachable
    return pool


def build_service(
    settings: Settings, store: VectorStore, embedder: Embedder, llm: LLMClient | None
) -> AssistantService:
    """The assistant as ``settings`` configure it. The answer-quality eval builds it through
    this function too, so it measures the same retrieval and LLM settings production runs."""
    return AssistantService(
        store=store,
        embedder=embedder,
        llm=llm,
        top_k=settings.retrieval_top_k,
        min_score=settings.retrieval_min_score,
        reranker=_build_reranker(settings),
        candidates=settings.retrieval_candidates,
        hybrid=settings.retrieval_hybrid,
    )


def build_embedder(settings: Settings) -> Embedder:
    if settings.embedding_provider == "hashing":
        log.warning("Using the hashing embedder: keyword matching only, not meant for real use")
        return HashingEmbedder()
    embedder = FastEmbedEmbedder(settings.embedding_model, cache_dir=settings.embedding_cache_dir)
    log.info("Embedding model: %s (%d dimensions)", embedder.name, embedder.dim)
    return embedder


def _build_reranker(settings: Settings) -> Reranker | None:
    if settings.embedding_provider == "hashing" or not settings.reranker_model:
        log.info("Reranking off: using vector scores only")
        return None
    reranker = CrossEncoderReranker(settings.reranker_model, cache_dir=settings.embedding_cache_dir)
    log.info("Reranker: %s (top %d candidates)", reranker.name, settings.retrieval_candidates)
    return reranker


def build_llm(settings: Settings) -> ClaudeClient | None:
    if not settings.llm_enabled:
        log.warning("ANTHROPIC_API_KEY not set: running in offline mode (no LLM calls)")
        return None
    return ClaudeClient(
        api_key=settings.anthropic_api_key.get_secret_value(),
        model=settings.llm_model,
        effort=settings.llm_effort,
        max_tokens=settings.llm_max_tokens,
        timeout_seconds=settings.llm_timeout_seconds,
    )


app = create_app()
