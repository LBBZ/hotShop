from __future__ import annotations

from datetime import datetime

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from hotshop_agent.config import Settings
from hotshop_agent.domain import IdentityKind, Principal
from hotshop_agent.security import AuthenticationError, ClientAssertionSigner, JwtVerifier


class TokenExchangeError(Exception):
    pass


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

    async def exchange(self, user_access_token: str, scopes: frozenset[str]) -> Principal:
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
                raise TokenExchangeError
            body = TokenExchangeResponse.model_validate(response.json())
            if body.token_type != "Bearer" or body.scopes != scopes:  # noqa: S105
                raise TokenExchangeError
            principal = self._verifier.verify(body.access_token, IdentityKind.DELEGATION)
            if principal.scopes != scopes:
                raise TokenExchangeError
            return principal
        except (
            httpx.HTTPError,
            AuthenticationError,
            ValueError,
            ValidationError,
            RuntimeError,
            OSError,
        ) as exc:
            raise TokenExchangeError from exc
