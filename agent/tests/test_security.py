from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
import pytest

from hotshop_agent.config import Settings
from hotshop_agent.domain import IdentityKind
from hotshop_agent.security import (
    ADMIN_AUTHORITIES,
    AuthenticationError,
    ClientAssertionSigner,
    JwtVerifier,
    bearer_credential,
)


def test_administrator_authorities_match_java_token_contract() -> None:
    assert ADMIN_AUTHORITIES == frozenset(
        {
            "ROLE_ADMIN",
            "PERM_ADMIN_PRODUCT_READ",
            "PERM_ADMIN_PRODUCT_WRITE",
            "PERM_ADMIN_FLASH_SALE_LOAD",
            "PERM_ADMIN_ORDER_READ",
            "PERM_ADMIN_USER_READ",
        }
    )


@pytest.mark.parametrize(
    ("actual", "expected"),
    [
        (IdentityKind.USER, IdentityKind.ADMINISTRATOR),
        (IdentityKind.USER, IdentityKind.DELEGATION),
        (IdentityKind.ADMINISTRATOR, IdentityKind.USER),
        (IdentityKind.ADMINISTRATOR, IdentityKind.DELEGATION),
        (IdentityKind.DELEGATION, IdentityKind.USER),
        (IdentityKind.DELEGATION, IdentityKind.ADMINISTRATOR),
    ],
)
def test_token_domains_cannot_be_interchanged(
    settings: Settings,
    issue_token: Any,
    actual: IdentityKind,
    expected: IdentityKind,
) -> None:
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(issue_token(actual), expected)


@pytest.mark.parametrize(
    ("claim_overrides", "header_overrides"),
    [
        ({"iss": "https://wrong.local"}, {}),
        ({"aud": "wrong-audience"}, {}),
        ({}, {"kid": "unknown"}),
        ({}, {"typ": "wrong+jwt"}),
        ({"exp": datetime.now(UTC) - timedelta(seconds=60)}, {}),
        ({"nbf": datetime.now(UTC) + timedelta(minutes=5)}, {}),
        ({"iat": datetime.now(UTC) + timedelta(minutes=5)}, {}),
    ],
)
def test_invalid_issuer_audience_kid_typ_and_times_are_rejected(
    settings: Settings,
    issue_token: Any,
    claim_overrides: dict[str, Any],
    header_overrides: dict[str, Any],
) -> None:
    token = issue_token(
        IdentityKind.USER,
        claim_overrides=claim_overrides,
        header_overrides=header_overrides,
    )
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(token, IdentityKind.USER)


def test_wrong_signature_is_rejected(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    token = issue_token(IdentityKind.USER, signing_key=key_material["wrong"][2])
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(token, IdentityKind.USER)


def test_rs256_is_required(settings: Settings, issue_token: Any) -> None:
    token = issue_token(IdentityKind.USER)
    header, payload, _signature = token.split(".")
    altered_header = jwt.utils.base64url_encode(
        b'{"alg":"none","typ":"user-access+jwt","kid":"user-kid"}'
    )
    unsigned = f"{altered_header.decode()}.{payload}."
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(unsigned, IdentityKind.USER)
    assert header


def test_required_time_claims_are_enforced(settings: Settings, issue_token: Any) -> None:
    token = issue_token(IdentityKind.USER, claim_overrides={"iat": None})
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(token, IdentityKind.USER)


def test_delegation_rejects_admin_authority(settings: Settings, issue_token: Any) -> None:
    token = issue_token(
        IdentityKind.DELEGATION,
        claim_overrides={"authorities": ["ROLE_ADMIN"]},
    )
    with pytest.raises(AuthenticationError):
        JwtVerifier(settings).verify(token, IdentityKind.DELEGATION)


def test_client_assertion_uses_unique_jti_and_expected_contract(
    settings: Settings,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    signer = ClientAssertionSigner(settings)
    first = signer.issue()
    second = signer.issue()
    public_key = key_material["assertion"][1].read_bytes()
    first_header = jwt.get_unverified_header(first)
    first_claims = jwt.decode(
        first,
        public_key,
        algorithms=["RS256"],
        issuer=settings.assertion_issuer,
        audience=settings.assertion_audience,
    )
    second_claims = jwt.decode(
        second,
        public_key,
        algorithms=["RS256"],
        issuer=settings.assertion_issuer,
        audience=settings.assertion_audience,
    )
    assert first_header == {
        "alg": "RS256",
        "kid": settings.assertion_kid,
        "typ": settings.assertion_type,
    }
    assert first_claims["sub"] == settings.assertion_client_id
    assert first_claims["jti"] != second_claims["jti"]
    assert first_claims["exp"] - first_claims["iat"] == 60


def test_bearer_scheme_is_strict(settings: Settings, issue_token: Any) -> None:
    token = issue_token(IdentityKind.USER)
    with pytest.raises(AuthenticationError):
        bearer_credential(f"bearer {token}", JwtVerifier(settings), IdentityKind.USER)
