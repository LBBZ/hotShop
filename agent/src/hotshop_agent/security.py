from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import jwt
from cryptography.hazmat.primitives import serialization
from jwt import InvalidTokenError

from hotshop_agent.config import Settings
from hotshop_agent.domain import Credential, IdentityKind, Principal

ADMIN_AUTHORITIES = frozenset(
    {
        "ROLE_ADMIN",
        "PERM_ADMIN_PRODUCT_READ",
        "PERM_ADMIN_PRODUCT_WRITE",
        "PERM_ADMIN_FLASH_SALE_LOAD",
        "PERM_ADMIN_ORDER_READ",
        "PERM_ADMIN_USER_READ",
    }
)
USER_AUTHORITIES = frozenset({"ROLE_USER"})
ALLOWED_DELEGATION_SCOPES = frozenset(
    {
        "catalog:read",
        "orders:self:read",
        "reservations:self:read",
        "purchase-drafts:create",
    }
)
BEARER_RE = re.compile(r"^Bearer ([^\s]+)$")


class AuthenticationError(Exception):
    """A deliberately detail-free authentication failure."""


@dataclass(frozen=True)
class JwtDomain:
    kind: IdentityKind
    issuer: str
    audience: str
    token_type: str
    token_use: str
    public_keys: dict[str, Path]
    max_ttl_seconds: int


class JwtVerifier:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._domains = {
            IdentityKind.USER: JwtDomain(
                IdentityKind.USER,
                settings.user_issuer,
                settings.user_audience,
                settings.user_type,
                settings.user_token_use,
                settings.user_public_keys,
                settings.user_max_ttl_seconds,
            ),
            IdentityKind.ADMINISTRATOR: JwtDomain(
                IdentityKind.ADMINISTRATOR,
                settings.administrator_issuer,
                settings.administrator_audience,
                settings.administrator_type,
                settings.administrator_token_use,
                settings.administrator_public_keys,
                settings.administrator_max_ttl_seconds,
            ),
            IdentityKind.DELEGATION: JwtDomain(
                IdentityKind.DELEGATION,
                settings.delegation_issuer,
                settings.delegation_audience,
                settings.delegation_type,
                settings.delegation_token_use,
                settings.delegation_public_keys,
                settings.delegation_max_ttl_seconds,
            ),
        }
        self._key_cache: dict[Path, bytes] = {}

    def verify(self, token: str, expected: IdentityKind) -> Principal:
        try:
            domain = self._domains[expected]
            header = jwt.get_unverified_header(token)
            if set(header).intersection({"jku", "jwk", "x5u", "crit"}):
                raise AuthenticationError
            if (
                header.get("alg") != "RS256"
                or header.get("typ") != domain.token_type
                or not isinstance(header.get("kid"), str)
            ):
                raise AuthenticationError
            key_path = domain.public_keys.get(header["kid"])
            if key_path is None:
                raise AuthenticationError
            claims = jwt.decode(
                token,
                self._load_public_key(key_path),
                algorithms=["RS256"],
                issuer=domain.issuer,
                audience=domain.audience,
                leeway=self._settings.jwt_clock_skew_seconds,
                options={
                    "require": [
                        "iss",
                        "aud",
                        "sub",
                        "iat",
                        "nbf",
                        "exp",
                        "jti",
                        "token_use",
                        "preferred_username",
                    ],
                    "verify_signature": True,
                    "verify_exp": True,
                    "verify_nbf": True,
                    "verify_iat": True,
                },
            )
            return self._validate_claims(claims, domain)
        except (AuthenticationError, InvalidTokenError, KeyError, OSError, ValueError, TypeError):
            raise AuthenticationError from None

    def _validate_claims(self, claims: dict[str, Any], domain: JwtDomain) -> Principal:
        required_text = ("sub", "jti", "preferred_username")
        if any(not isinstance(claims.get(name), str) or not claims[name] for name in required_text):
            raise AuthenticationError
        if claims.get("iss") != domain.issuer or claims.get("aud") != domain.audience:
            raise AuthenticationError
        if claims.get("token_use") != domain.token_use:
            raise AuthenticationError
        if not claims["sub"].isdigit() or int(claims["sub"]) <= 0:
            raise AuthenticationError
        issued_at = self._numeric_time(claims, "iat")
        not_before = self._numeric_time(claims, "nbf")
        expires_at = self._numeric_time(claims, "exp")
        skew = self._settings.jwt_clock_skew_seconds
        if (
            not_before < issued_at - skew
            or expires_at <= issued_at
            or expires_at - issued_at > domain.max_ttl_seconds + skew
        ):
            raise AuthenticationError

        authorities = claims.get("authorities")
        authorized_party: str | None = None
        scopes: frozenset[str] = frozenset()
        if domain.kind is IdentityKind.DELEGATION:
            authorized_party = claims.get("azp")
            raw_scope = claims.get("scope")
            if (
                authorized_party != self._settings.assertion_client_id
                or not isinstance(raw_scope, str)
                or authorities is not None
                or "roles" in claims
            ):
                raise AuthenticationError
            scopes = frozenset(raw_scope.split())
            if not scopes or not ALLOWED_DELEGATION_SCOPES.issuperset(scopes):
                raise AuthenticationError
        else:
            expected_authorities = (
                USER_AUTHORITIES if domain.kind is IdentityKind.USER else ADMIN_AUTHORITIES
            )
            if (
                not isinstance(authorities, list)
                or frozenset(authorities) != expected_authorities
                or "azp" in claims
                or "scope" in claims
            ):
                raise AuthenticationError

        return Principal(
            kind=domain.kind,
            subject_user_id=claims["sub"],
            username=claims["preferred_username"],
            jti=claims["jti"],
            expires_at=datetime.fromtimestamp(expires_at, UTC),
            authorized_party=authorized_party,
            scopes=scopes,
        )

    @staticmethod
    def _numeric_time(claims: dict[str, Any], name: str) -> int:
        value = claims.get(name)
        if not isinstance(value, int) or isinstance(value, bool):
            raise AuthenticationError
        return value

    def _load_public_key(self, path: Path) -> bytes:
        if path not in self._key_cache:
            self._key_cache[path] = path.read_bytes()
        return self._key_cache[path]


class ClientAssertionSigner:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._private_key: bytes | None = None

    def issue(self, now: datetime | None = None) -> str:
        path = self._settings.assertion_private_key_path
        if path is None:
            raise RuntimeError("Agent Service assertion key is not configured")
        if self._private_key is None:
            raw_key = path.read_bytes()
            serialization.load_pem_private_key(raw_key, password=None)
            self._private_key = raw_key
        issued_at = (now or datetime.now(UTC)).replace(microsecond=0)
        claims = {
            "iss": self._settings.assertion_issuer,
            "aud": self._settings.assertion_audience,
            "sub": self._settings.assertion_client_id,
            "iat": issued_at,
            "nbf": issued_at,
            "exp": issued_at + timedelta(seconds=self._settings.assertion_ttl_seconds),
            "jti": str(uuid.uuid4()),
        }
        return jwt.encode(
            claims,
            self._private_key,
            algorithm="RS256",
            headers={
                "kid": self._settings.assertion_kid,
                "typ": self._settings.assertion_type,
            },
        )


def bearer_credential(
    authorization: str | None,
    verifier: JwtVerifier,
    kind: IdentityKind,
) -> Credential:
    match = BEARER_RE.fullmatch(authorization or "")
    if match is None:
        raise AuthenticationError
    token = match.group(1)
    return Credential(token=token, principal=verifier.verify(token, kind))


def safe_json(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)
