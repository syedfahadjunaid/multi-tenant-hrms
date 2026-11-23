"""Settings, read from environment variables (and ../../.env when run locally).

pydantic-settings maps each field to an env var of the same name, case-insensitive:
``jwt_issuer_uri`` <- ``JWT_ISSUER_URI``. Secrets are ``SecretStr`` so they never show
up in logs or tracebacks by accident.
"""

from typing import Literal

from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Database (ai_svc login, owns schema "ai")
    db_host: str = "localhost"
    db_port: int = Field(
        default=15432, validation_alias=AliasChoices("DB_PORT", "POSTGRES_HOST_PORT")
    )
    db_name: str = "hrms"
    db_user: str = "ai_svc"
    ai_db_password: SecretStr = SecretStr("")

    # JWT verification (same rules as the Java service)
    jwt_issuer_uri: str = "http://localhost:8180/realms/hrms"
    jwt_jwk_set_uri: str = "http://localhost:8180/realms/hrms/protocol/openid-connect/certs"
    jwt_audience: str = "hrms-api"

    # LLM. Without an API key the service runs in offline mode (no LLM calls, no cost).
    anthropic_api_key: SecretStr | None = None
    llm_model: str = "claude-opus-5-5"
    llm_effort: Literal["low", "medium", "high", "xhigh", "max"] = "medium"
    llm_max_tokens: int = 16000
    llm_timeout_seconds: float = 120.0

    # Retrieval
    # "local": a real embedding model run on this machine (fastembed/ONNX, no API key).
    # "hashing": keyword matching only. For tests and as a baseline; don't use for real answers.
    embedding_provider: Literal["local", "hashing"] = "local"
    embedding_model: str = "BAAI/bge-small-en-v1.5"
    # Where model files are cached. The Docker image bakes them in, so nothing is downloaded
    # at runtime.
    embedding_cache_dir: str | None = None
    # Optional second stage: a cross-encoder re-orders the top candidates. Off by default:
    # on the 66-document eval it ranks better (top-1 +7-8 points) but the end-to-end gain
    # (+3 points) isn't statistically clear, and it costs ~0.7 s per question on a CPU.
    # Best option if you turn it on: "Xenova/ms-marco-MiniLM-L-6-v2" (bake it into the image
    # with the RERANKER_MODEL build arg).
    reranker_model: str = ""
    # Hybrid search: also find chunks by keywords (Postgres full-text search) and merge both
    # rankings with reciprocal rank fusion. The embedding still decides "not found".
    retrieval_hybrid: bool = False
    retrieval_candidates: int = 20  # candidates per search (and what the reranker re-orders)
    retrieval_top_k: int = 5  # how many passages the answer may use
    # Cut-off on the final score (the reranker's, if enabled). Leave unset to use the value
    # calibrated for the model (see evals/retrieval).
    retrieval_min_score: float | None = None

    enable_docs: bool = True

    @property
    def llm_enabled(self) -> bool:
        return bool(self.anthropic_api_key and self.anthropic_api_key.get_secret_value().strip())
