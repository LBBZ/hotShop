from __future__ import annotations

import logging
from datetime import datetime

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from hotshop_agent.config import Settings
from hotshop_agent.domain import Credential, IdentityKind
from hotshop_agent.security import AuthenticationError, ClientAssertionSigner, JwtVerifier


class TokenExchangeError(Exception):
    pass


def _failure(category: str) -> TokenExchangeError:
    logging.getLogger(__name__).warning(
        "token exchange failed",
        extra={
            "event": "agent.token_exchange.failed",
            "outcome": "unavailable",
            "errorType": category,
            "parameterSummary": "credentials_omitted",
        },
    )
    return TokenExchangeError()


class TokenExchangeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token_type: str = Field(alias="tokenType")
    access_token: str = Field(alias="accessToken", repr=False)
    expires_at: datetime = Field(alias="expiresAt")
    scopes: frozenset[str]


class TokenExchangeClient:
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient,
        signer: ClientAssertionSigner,
        verifier: JwtVerifier,
    ) -> None:
        self._settings = settings
        self._client = client
        self._signer = signer
        self._verifier = verifier

    async def exchange(self, user_access_token: str, scopes: frozenset[str]) -> Credential:
        try:
            assertion = self._signer.issue()
            response = await self._client.post(
                f"{self._settings.portal_base_url}{self._settings.token_exchange_path}",
                json={
                    "subjectToken": user_access_token,
                    "clientAssertion": assertion,
                    "scopes": sorted(scopes),
                },
                timeout=self._settings.token_exchange_timeout_seconds,
                headers={"Accept": "application/json"},
            )
            if response.status_code != 200:
                raise _failure(
                    "exchange_rate_limit" if response.status_code == 429 else "exchange_http"
                )
            body = TokenExchangeResponse.model_validate(response.json())
            if body.token_type != "Bearer" or body.scopes != scopes:  # noqa: S105
                raise _failure("exchange_response")
            principal = self._verifier.verify(body.access_token, IdentityKind.DELEGATION)
            if principal.scopes != scopes:
                raise _failure("exchange_response")
            return Credential(token=body.access_token, principal=principal)
        except (
            httpx.HTTPError,
            AuthenticationError,
            ValueError,
            ValidationError,
            RuntimeError,
            OSError,
        ) as exc:
            category = "exchange_validation"
            if isinstance(exc, httpx.ConnectTimeout):
                category = "exchange_connect_timeout"
            elif isinstance(exc, httpx.ReadTimeout):
                category = "exchange_read_timeout"
            elif isinstance(exc, httpx.TimeoutException):
                category = "exchange_timeout"
            elif isinstance(exc, httpx.ConnectError):
                category = "exchange_connect"
            elif isinstance(exc, httpx.HTTPError):
                category = "exchange_transport"
            elif isinstance(exc, RuntimeError | OSError):
                category = "exchange_signing"
            raise _failure(category) from exc
