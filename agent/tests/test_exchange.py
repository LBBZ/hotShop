from __future__ import annotations

import logging

import httpx
import pytest

from hotshop_agent.config import Settings
from hotshop_agent.exchange import TokenExchangeClient, TokenExchangeError
from hotshop_agent.security import ClientAssertionSigner, JwtVerifier


@pytest.mark.asyncio
async def test_exchange_failure_is_sanitized(
    settings: Settings,
    caplog: pytest.LogCaptureFixture,
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
    assert "secret" not in caplog.text
    assert getattr(caplog.records[-1], "errorType", None) == "exchange_http"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("failure", "category"),
    [
        (httpx.ReadTimeout("private-response"), "exchange_read_timeout"),
        (httpx.ConnectTimeout("private-host"), "exchange_connect_timeout"),
        (httpx.ConnectError("private-host"), "exchange_connect"),
        (httpx.RemoteProtocolError("private-response"), "exchange_transport"),
        (429, "exchange_rate_limit"),
        (503, "exchange_http"),
        (200, "exchange_validation"),
    ],
)
async def test_exchange_diagnostics_explain_failures_without_credentials(
    settings: Settings,
    caplog: pytest.LogCaptureFixture,
    failure: httpx.HTTPError | int,
    category: str,
) -> None:
    def respond(_request: httpx.Request) -> httpx.Response:
        if isinstance(failure, httpx.HTTPError):
            raise failure
        return httpx.Response(failure, json={"detail": "private-response"})

    caplog.set_level(logging.WARNING)
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        exchange = TokenExchangeClient(
            settings, client, ClientAssertionSigner(settings), JwtVerifier(settings)
        )
        with pytest.raises(TokenExchangeError):
            await exchange.exchange("private-user-token", frozenset({"catalog:read"}))
    record = caplog.records[-1]
    assert getattr(record, "event", None) == "agent.token_exchange.failed"
    assert getattr(record, "errorType", None) == category
    assert "private-" not in caplog.text
