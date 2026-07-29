from __future__ import annotations

import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import jwt
import pytest

from hotshop_agent.api import create_app
from hotshop_agent.config import Settings
from hotshop_agent.container import Container, build_container
from hotshop_agent.domain import IdentityKind
from hotshop_agent.providers.base import ModelChunk, ModelDelta, ModelUsage


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parse_sse(body: str) -> list[dict[str, Any]]:
    return [json.loads(line[6:]) for line in body.splitlines() if line.startswith("data: ")]


def exchange_transport(
    settings: Settings,
    issue_token: Any,
    assertion_public_key: bytes,
    calls: list[dict[str, Any]],
) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assertion = jwt.decode(
            body["clientAssertion"],
            assertion_public_key,
            algorithms=["RS256"],
            issuer=settings.assertion_issuer,
            audience=settings.assertion_audience,
        )
        calls.append({"body": body, "assertion": assertion})
        delegation = issue_token(
            IdentityKind.DELEGATION,
            claim_overrides={"scope": " ".join(sorted(body["scopes"]))},
        )
        return httpx.Response(
            200,
            json={
                "tokenType": "Bearer",
                "accessToken": delegation,
                "expiresAt": (datetime.now(UTC) + timedelta(minutes=5)).isoformat(),
                "scopes": body["scopes"],
            },
        )

    return httpx.MockTransport(handler)


async def make_client(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
    calls: list[dict[str, Any]],
    *,
    provider: Any = None,
) -> tuple[httpx.AsyncClient, Container]:
    internal = httpx.AsyncClient(
        transport=exchange_transport(
            settings,
            issue_token,
            key_material["assertion"][1].read_bytes(),
            calls,
        )
    )
    container = build_container(settings, http_client=internal, provider=provider)
    app = create_app(settings, container=container)
    client = httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://agent.test",
    )
    return client, container


@pytest.mark.asyncio
async def test_fake_model_complete_sse_without_api_key(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    assert settings.qwen_api_key is None
    calls: list[dict[str, Any]] = []
    client, container = await make_client(settings, issue_token, key_material, calls)
    try:
        token = issue_token(IdentityKind.USER)
        session_response = await client.post(
            "/api/v1/agent/sessions",
            json={},
            headers=auth(token),
        )
        assert session_response.status_code == 201
        session_id = session_response.json()["id"]
        message_response = await client.post(
            f"/api/v1/agent/sessions/{session_id}/messages",
            json={"content": "recommend something"},
            headers=auth(token),
        )
        assert message_response.status_code == 201, message_response.text
        run_response = await client.post(
            f"/api/v1/agent/sessions/{session_id}/runs",
            json={"messageId": message_response.json()["id"]},
            headers=auth(token),
        )
        assert run_response.status_code == 202
        event_response = await client.get(
            f"/api/v1/agent/runs/{run_response.json()['id']}/events",
            headers=auth(token),
        )
        assert event_response.status_code == 200
        assert event_response.headers["content-type"].startswith("text/event-stream")
        events = parse_sse(event_response.text)
        assert [event["type"] for event in events] == [
            "session.created",
            "message.started",
            "message.delta",
            "message.delta",
            "message.delta",
            "usage",
            "message.completed",
            "done",
        ]
        assert (
            "".join(event["data"]["delta"] for event in events if event["type"] == "message.delta")
            == "HotShop FakeModel response."
        )
        assert events[-1]["data"]["state"] == "COMPLETED"
        assert len(calls) == 1
        assert calls[0]["body"]["subjectToken"] == token
    finally:
        await client.aclose()
        await container.close()


@pytest.mark.asyncio
async def test_user_and_administrator_routes_are_isolated_and_admin_does_not_exchange(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    calls: list[dict[str, Any]] = []
    client, container = await make_client(settings, issue_token, key_material, calls)
    try:
        user = issue_token(IdentityKind.USER)
        administrator = issue_token(IdentityKind.ADMINISTRATOR)
        assert (
            await client.post(
                "/admin/api/v1/agent/sessions",
                json={},
                headers=auth(user),
            )
        ).status_code == 401
        assert (
            await client.post(
                "/api/v1/agent/sessions",
                json={},
                headers=auth(administrator),
            )
        ).status_code == 401
        admin_session = await client.post(
            "/admin/api/v1/agent/sessions",
            json={},
            headers=auth(administrator),
        )
        assert admin_session.status_code == 201
        assert admin_session.json()["scopes"] == []
        assert calls == []
    finally:
        await client.aclose()
        await container.close()


class SensitiveModel:
    name = "sensitive-test"

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        yield ModelDelta(
            "Bearer abc.def.ghi api_key=paid-secret <thinking>hidden reasoning</thinking>"
        )
        yield ModelUsage(1, 1, 0.0)


@pytest.mark.asyncio
async def test_sse_redacts_tokens_keys_and_hidden_reasoning(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    calls: list[dict[str, Any]] = []
    client, container = await make_client(
        settings,
        issue_token,
        key_material,
        calls,
        provider=SensitiveModel(),
    )
    try:
        token = issue_token(IdentityKind.USER)
        session = (await client.post("/api/v1/agent/sessions", json={}, headers=auth(token))).json()
        message = (
            await client.post(
                f"/api/v1/agent/sessions/{session['id']}/messages",
                json={"content": "test"},
                headers=auth(token),
            )
        ).json()
        run = (
            await client.post(
                f"/api/v1/agent/sessions/{session['id']}/runs",
                json={"messageId": message["id"]},
                headers=auth(token),
            )
        ).json()
        body = (
            await client.get(
                f"/api/v1/agent/runs/{run['id']}/events",
                headers=auth(token),
            )
        ).text
        lowered = body.lower()
        assert "paid-secret" not in body
        assert "abc.def.ghi" not in body
        assert "hidden reasoning" not in lowered
        assert "system prompt" not in lowered
        assert "[REDACTED]" in body
    finally:
        await client.aclose()
        await container.close()


@pytest.mark.asyncio
async def test_assertion_jti_is_never_reused_across_sessions(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    calls: list[dict[str, Any]] = []
    client, container = await make_client(settings, issue_token, key_material, calls)
    try:
        token = issue_token(IdentityKind.USER)
        for _ in range(2):
            response = await client.post(
                "/api/v1/agent/sessions",
                json={},
                headers=auth(token),
            )
            assert response.status_code == 201
        assert calls[0]["assertion"]["jti"] != calls[1]["assertion"]["jti"]
    finally:
        await client.aclose()
        await container.close()


@pytest.mark.asyncio
async def test_health_and_readiness_start_with_fake_and_no_api_key() -> None:
    settings = Settings(environment="test")
    container = build_container(settings)
    app = create_app(settings, container=container)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://agent.test",
    ) as client:
        assert (await client.get("/health/live")).json() == {"status": "UP"}
        assert (await client.get("/health/ready")).json() == {
            "status": "READY",
            "provider": "fake",
        }
    await container.close()


@pytest.mark.asyncio
async def test_dynamic_tool_and_arbitrary_request_fields_are_rejected(
    settings: Settings,
    issue_token: Any,
    key_material: dict[str, tuple[Any, Any, bytes]],
) -> None:
    calls: list[dict[str, Any]] = []
    client, container = await make_client(settings, issue_token, key_material, calls)
    try:
        token = issue_token(IdentityKind.USER)
        response = await client.post(
            "/api/v1/agent/sessions",
            json={"tool": "shell", "url": "https://example.test", "sql": "select 1"},
            headers=auth(token),
        )
        assert response.status_code == 400
        assert calls == []
    finally:
        await client.aclose()
        await container.close()
