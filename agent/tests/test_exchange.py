from __future__ import annotations

import httpx
import pytest

from hotshop_agent.config import Settings
from hotshop_agent.exchange import TokenExchangeClient, TokenExchangeError
from hotshop_agent.security import ClientAssertionSigner, JwtVerifier


@pytest.mark.asyncio
async def test_exchange_failure_is_sanitized(
    settings: Settings,
) -> None:
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            401,
            json={"detail": "clientAssertion=secret-token-value"},
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        exchange = TokenExchangeClient(
            settings,
            client,
            ClientAssertionSigner(settings),
            JwtVerifier(settings),
        )
        with pytest.raises(TokenExchangeError) as caught:
            await exchange.exchange("user-secret-token", frozenset({"catalog:read"}))
    assert "secret" not in str(caught.value)
