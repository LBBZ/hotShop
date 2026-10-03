from __future__ import annotations

import asyncio
import json
import logging

import httpx
import pytest

from hotshop_agent.api import create_app
from hotshop_agent.config import Settings
from hotshop_agent.observability import (
    TRACE_ID,
    JsonFormatter,
    Telemetry,
    parse_remote_parent,
    sanitize,
)


def test_w3c_parent_and_sanitizer_are_bounded() -> None:
    parent = parse_remote_parent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
    assert parent is not None
    assert parent.trace_id == "4bf92f3577b34da6a3ce929d0e0e4736"
    sentinel = "TASK11_SENTINEL_SECRET"
    safe = sanitize(f"Authorization: Bearer {sentinel} password={sentinel}")
    assert sentinel not in safe
    assert "[REDACTED]" in safe


def test_json_formatter_never_serializes_secret_or_body() -> None:
    sentinel = "TASK11_SENTINEL_SECRET"
    formatter = JsonFormatter("agent", "test")
    record = logging.LogRecord(
        "hotshop_agent.test",
        logging.INFO,
        __file__,
        1,
        f"apiKey={sentinel}",
        (),
        None,
    )
    parsed = json.loads(formatter.format(record))
    assert sentinel not in json.dumps(parsed)
    assert {
        "timestamp",
        "level",
        "service",
        "environment",
        "event",
        "name",
        "requestId",
        "traceId",
        "spanId",
        "outcome",
        "errorType",
        "message",
    }.issubset(parsed)


@pytest.mark.asyncio
async def test_http_context_preserves_request_and_trace_ids() -> None:
    settings = Settings(environment="test", state_backend="memory", trace_sample_ratio=0)
    app = create_app(settings)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agent") as client:
        response = await client.get(
            "/health/live",
            headers={
                "X-Request-ID": "browser-request-11",
                "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "tracestate": "vendor=value",
            },
        )
    assert response.status_code == 200
    assert response.headers["X-Request-ID"] == "browser-request-11"
    assert response.headers["X-Trace-ID"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert TRACE_ID.get() == ""


@pytest.mark.asyncio
async def test_slow_trace_collector_cannot_fan_out_unbounded_requests() -> None:
    release = asyncio.Event()
    calls = 0

    async def collect(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        await release.wait()
        return httpx.Response(202)

    async with httpx.AsyncClient(transport=httpx.MockTransport(collect)) as client:
        telemetry = Telemetry(Settings(trace_sample_ratio=1), client)
        try:
            async with asyncio.timeout(1):
                for _ in range(100):
                    async with telemetry.span("catalog.read"):
                        pass
                    await asyncio.sleep(0)
            assert calls == 2
        finally:
            release.set()
            await telemetry.close()


@pytest.mark.asyncio
async def test_failed_trace_collector_backs_off_then_resumes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    clock = 100.0
    monkeypatch.setattr("hotshop_agent.observability.time.perf_counter", lambda: clock)
    calls = 0

    def collect(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503 if calls == 1 else 202)

    async with httpx.AsyncClient(transport=httpx.MockTransport(collect)) as client:
        telemetry = Telemetry(Settings(trace_sample_ratio=1), client)
        async with telemetry.span("first"):
            pass
        await telemetry.close()
        for _ in range(20):
            async with telemetry.span("collector.unavailable"):
                pass
        await telemetry.close()
        assert calls == 1
        clock += 31
        async with telemetry.span("collector.recovered"):
            pass
        await telemetry.close()
        assert calls == 2
