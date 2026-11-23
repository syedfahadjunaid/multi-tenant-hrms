"""JWT verification and role checks.

This service does NOT trust the Java service. Every request carries the end user's own
Keycloak token (token relay), and we verify it again here: signature, expiry, issuer,
audience and tenant. A compromised or buggy caller cannot ask about another tenant's data.
"""

import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import jwt
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

log = logging.getLogger(__name__)

KeyResolver = Callable[[str], Any]


@dataclass(frozen=True)
class Principal:
    """The verified caller. ``tenant_id`` comes only from the signed token."""

    subject: str
    tenant_id: str
    roles: frozenset[str]
    username: str | None = None

    def has_role(self, role: str) -> bool:
        return role in self.roles


class AuthError(Exception):
    pass


class AuthUnavailableError(Exception):
    """The identity provider's keys could not be fetched (an outage, not a bad token)."""


class TokenVerifier:
    def __init__(self, issuer: str, audience: str, key_resolver: KeyResolver) -> None:
        self._issuer = issuer
        self._audience = audience
        self._key_resolver = key_resolver

    def verify(self, token: str) -> Principal:
        try:
            key = self._key_resolver(token)
            claims = jwt.decode(
                token,
                key,
                algorithms=["RS256"],  # never accept "none" or a symmetric algorithm
                audience=self._audience,
                issuer=self._issuer,
                leeway=30,
                options={"require": ["exp", "iat", "iss", "aud", "sub"]},
            )
        except jwt.PyJWKClientConnectionError as e:
            raise AuthUnavailableError(str(e)) from e
        except jwt.PyJWTError as e:
            raise AuthError(str(e)) from e

        tenant = claims.get("tenant_id")
        if not isinstance(tenant, str) or not tenant.strip():
            raise AuthError("token has no tenant_id claim")

        realm_access = claims.get("realm_access")
        raw_roles = realm_access.get("roles", []) if isinstance(realm_access, dict) else []
        roles = frozenset(r for r in raw_roles if isinstance(r, str))

        return Principal(
            subject=claims["sub"],
            tenant_id=tenant,
            roles=roles,
            username=claims.get("preferred_username"),
        )


def jwks_key_resolver(jwks_url: str) -> KeyResolver:
    """Fetches Keycloak's public keys and caches them (refetched when a new key id appears)."""
    client = jwt.PyJWKClient(jwks_url, cache_keys=True, lifespan=300, timeout=5)
    return lambda token: client.get_signing_key_from_jwt(token).key


_bearer = HTTPBearer(auto_error=False)


def get_principal(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),  # noqa: B008
) -> Principal:
    if credentials is None:
        raise _unauthorized("missing bearer token")
    verifier: TokenVerifier = request.app.state.verifier
    try:
        return verifier.verify(credentials.credentials)
    except AuthUnavailableError as e:
        log.error("JWKS unavailable: %s", e)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Authentication unavailable"
        ) from e
    except AuthError as e:
        log.info("Rejected token: %s", e)
        raise _unauthorized("invalid token") from e


def require_role(role: str) -> Callable[[Principal], Principal]:
    def checker(principal: Principal = Depends(get_principal)) -> Principal:  # noqa: B008
        if not principal.has_role(role):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Forbidden")
        return principal

    return checker


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(
        status.HTTP_401_UNAUTHORIZED, detail, headers={"WWW-Authenticate": "Bearer"}
    )
