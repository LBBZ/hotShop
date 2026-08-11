from __future__ import annotations

from collections.abc import Callable, Generator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from hotshop_agent.config import Settings
from hotshop_agent.domain import IdentityKind


def generate_key_pair(directory: Path, name: str) -> tuple[Path, Path, bytes]:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_bytes = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    public_bytes = private_key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    private_path = directory / f"{name}-private.pem"
    public_path = directory / f"{name}-public.pem"
    private_path.write_bytes(private_bytes)
    public_path.write_bytes(public_bytes)
    return private_path, public_path, private_bytes


@pytest.fixture
def key_material(tmp_path: Path) -> dict[str, tuple[Path, Path, bytes]]:
    return {
        name: generate_key_pair(tmp_path, name)
        for name in ("user", "administrator", "delegation", "assertion", "wrong")
    }


@pytest.fixture
def settings(key_material: dict[str, tuple[Path, Path, bytes]]) -> Settings:
    return Settings(
        environment="test",
        model_provider="fake",
        state_backend="memory",
        assertion_private_key_path=key_material["assertion"][0],
        user_public_keys={"user-kid": key_material["user"][1]},
        administrator_public_keys={"admin-kid": key_material["administrator"][1]},
        delegation_public_keys={"delegation-kid": key_material["delegation"][1]},
        model_retry_base_seconds=0,
    )


@pytest.fixture
def issue_token(
    settings: Settings,
    key_material: dict[str, tuple[Path, Path, bytes]],
) -> Generator[Callable[..., str], None, None]:
    domains = {
        IdentityKind.USER: (
            settings.user_issuer,
            settings.user_audience,
            settings.user_type,
            settings.user_token_use,
            "user-kid",
            key_material["user"][2],
        ),
        IdentityKind.ADMINISTRATOR: (
            settings.administrator_issuer,
            settings.administrator_audience,
            settings.administrator_type,
            settings.administrator_token_use,
            "admin-kid",
            key_material["administrator"][2],
        ),
        IdentityKind.DELEGATION: (
            settings.delegation_issuer,
            settings.delegation_audience,
            settings.delegation_type,
            settings.delegation_token_use,
            "delegation-kid",
            key_material["delegation"][2],
        ),
    }

    def issue(
        kind: IdentityKind,
        *,
        claim_overrides: dict[str, Any] | None = None,
        header_overrides: dict[str, Any] | None = None,
        signing_key: bytes | None = None,
    ) -> str:
        issuer, audience, token_type, token_use, kid, private_key = domains[kind]
        now = datetime.now(UTC).replace(microsecond=0)
        ttl = 300 if kind is IdentityKind.DELEGATION else 900
        claims: dict[str, Any] = {
            "iss": issuer,
            "aud": audience,
            "sub": "42",
            "iat": now,
            "nbf": now,
            "exp": now + timedelta(seconds=ttl),
            "jti": f"{kind}-jti",
            "token_use": token_use,
            "preferred_username": "alice",
        }
        if kind is IdentityKind.USER:
            claims["authorities"] = ["ROLE_USER"]
        elif kind is IdentityKind.ADMINISTRATOR:
            claims["authorities"] = [
                "ROLE_ADMIN",
                "PERM_ADMIN_PRODUCT_READ",
                "PERM_ADMIN_PRODUCT_WRITE",
                "PERM_ADMIN_FLASH_SALE_LOAD",
                "PERM_ADMIN_ORDER_READ",
                "PERM_ADMIN_USER_READ",
            ]
        else:
            claims["azp"] = settings.assertion_client_id
            claims["scope"] = "catalog:read orders:self:read reservations:self:read"
        claims.update(claim_overrides or {})
        headers = {"kid": kid, "typ": token_type}
        headers.update(header_overrides or {})
        return jwt.encode(
            claims,
            signing_key or private_key,
            algorithm="RS256",
            headers=headers,
        )

    yield issue
