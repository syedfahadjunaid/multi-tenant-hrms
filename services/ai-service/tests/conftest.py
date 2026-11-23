"""Shared test fixtures.

We generate a throwaway RSA key and sign our own JWTs with it, so tests exercise the real
verification code (signature, issuer, audience, expiry) without running Keycloak.
"""

import time
from collections.abc import Callable, Iterator

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.rag.embeddings import HashingEmbedder
from app.rag.service import AssistantService
from app.rag.store import InMemoryVectorStore
from app.security import TokenVerifier

ISSUER = "http://test-issuer/realms/hrms"
AUDIENCE = "hrms-api"

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_public_key = _private_key.public_key()

TokenFactory = Callable[..., str]


@pytest.fixture
def make_token() -> TokenFactory:
    def factory(
        tenant: str | None = "acme",
        roles: tuple[str, ...] = ("EMPLOYEE",),
        sub: str = "user-1",
        audience: str = AUDIENCE,
        issuer: str = ISSUER,
        expires_in: int = 300,
        key=_private_key,
    ) -> str:
        now = int(time.time())
        claims = {
            "sub": sub,
            "iss": issuer,
            "aud": [audience, "account"],
            "iat": now,
            "exp": now + expires_in,
            "preferred_username": sub,
            "realm_access": {"roles": list(roles)},
        }
        if tenant is not None:
            claims["tenant_id"] = tenant
        return jwt.encode(claims, key, algorithm="RS256")

    return factory


@pytest.fixture
def store() -> InMemoryVectorStore:
    return InMemoryVectorStore()


@pytest.fixture
def client(store: InMemoryVectorStore) -> Iterator[TestClient]:
    service = AssistantService(store=store, embedder=HashingEmbedder(256), llm=None)
    verifier = TokenVerifier(ISSUER, AUDIENCE, key_resolver=lambda _token: _public_key)
    app = create_app(Settings(_env_file=None), service=service, verifier=verifier)
    with TestClient(app) as test_client:
        yield test_client


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
