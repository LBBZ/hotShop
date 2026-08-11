from __future__ import annotations

import asyncio
import uuid
from collections.abc import AsyncIterator
from typing import Any

import pytest
from fastapi import Request

from hotshop_agent.api import create_app
from hotshop_agent.config import Settings
from hotshop_agent.container import build_container
from hotshop_agent.domain import Credential, IdentityKind, RunState
from hotshop_agent.providers.base import ModelCapabilities, ModelChunk, ModelDelta
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.security import JwtVerifier
from hotshop_agent.service import RunHandle


class FloodModel:
    name = "flood"
    model_name = "flood"
    capabilities = ModelCapabilities("fake", "fake", True, True, "not_provided")

    def __init__(self) -> None:
        self.active_calls = 0

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        self.active_calls += 1
        try:
            for index in range(512):
                yield ModelDelta(f"delta-{index:04d}|")
        finally:
            self.active_calls -= 1


async def wait_until_queue_is_full(handle: RunHandle, provider: FloodModel) -> None:
    for _ in range(100):
        if handle.queue.full() and provider.active_calls == 1:
            return
        await asyncio.sleep(0.01)
    raise AssertionError("run did not reach full-queue backpressure")


async def start_flooded_run(
    settings: Settings,
    issue_token: Any,
) -> tuple[Any, Any, Any, RunHandle, FloodModel]:
    provider = FloodModel()
    container = build_container(settings, provider=provider)
    token = issue_token(IdentityKind.USER)
    principal = JwtVerifier(settings).verify(token, IdentityKind.USER)
    session = await container.service.create_session(principal)
    message = await container.service.add_message(session.id, principal, "flood")
    run = await container.service.start_run(session.id, message.id, principal)
    handle = await container.service.handle(run.id, principal)
    await wait_until_queue_is_full(handle, provider)
    return container, principal, run, handle, provider


@pytest.mark.asyncio
async def test_explicit_cancel_leaves_no_model_task(
    settings: Settings,
    issue_token: Any,
) -> None:
    provider = FakeModel(delay_seconds=0.2)
    container = build_container(settings, provider=provider)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    session = await container.service.create_session(principal, scopes=frozenset({"catalog:read"}))
    message = await container.service.add_message(session.id, principal, "slow")
    run = await container.service.start_run(session.id, message.id, principal)
    for _ in range(50):
        if provider.active_calls:
            break
        await asyncio.sleep(0.01)
    cancelled = await container.service.cancel(run.id, principal)
    assert cancelled.state is RunState.CANCELLED
    assert provider.active_calls == 0
    assert container.service.model.limiter.global_active == 0
    await container.close()


@pytest.mark.asyncio
async def test_client_disconnect_closes_stream_and_cancels_model(
    settings: Settings,
    issue_token: Any,
) -> None:
    provider = FakeModel(delay_seconds=0.2)
    container = build_container(settings, provider=provider)
    app = create_app(settings, container=container)
    token = issue_token(IdentityKind.USER)
    principal = JwtVerifier(settings).verify(token, IdentityKind.USER)
    credential = Credential(token=token, principal=principal)
    session = await container.service.create_session(principal, scopes=frozenset({"catalog:read"}))
    message = await container.service.add_message(session.id, principal, "slow")
    run = await container.service.start_run(session.id, message.id, principal)

    route = next(
        route
        for route in app.routes
        if getattr(route, "path", "") == "/api/v1/agent/runs/{run_id}/events"
    )

    async def receive() -> dict[str, str]:
        return {"type": "http.request", "body": ""}

    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": f"/api/v1/agent/runs/{run.id}/events",
            "headers": [],
            "app": app,
        },
        receive=receive,
    )
    response = await route.endpoint(  # type: ignore[attr-defined]
        uuid.UUID(run.id),
        request,
        container,
        f"Bearer {credential.token}",
    )
    iterator = response.body_iterator
    _ = await anext(iterator)
    await iterator.aclose()
    for _ in range(50):
        current = await container.store.get_run(run.id)
        if current is not None and current.state is RunState.CANCELLED:
            break
        await asyncio.sleep(0.01)
    current = await container.store.get_run(run.id)
    assert current is not None and current.state is RunState.CANCELLED
    assert provider.active_calls == 0
    await container.close()


@pytest.mark.asyncio
async def test_shutdown_cancels_all_background_runs(settings: Settings, issue_token: Any) -> None:
    provider = FakeModel(delay_seconds=1)
    container = build_container(settings, provider=provider)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    session = await container.service.create_session(principal)
    message = await container.service.add_message(session.id, principal, "slow")
    await container.service.start_run(session.id, message.id, principal)
    await asyncio.sleep(0.05)
    await container.service.shutdown()
    assert provider.active_calls == 0
    await container.http_client.aclose()
    await container.store.close()


@pytest.mark.asyncio
async def test_full_queue_cancel_is_bounded_and_persists_cancelled(
    settings: Settings,
    issue_token: Any,
) -> None:
    container, principal, run, handle, provider = await start_flooded_run(
        settings,
        issue_token,
    )
    try:
        cancelled = await asyncio.wait_for(
            container.service.cancel(run.id, principal),
            timeout=1,
        )
        assert cancelled.state is RunState.CANCELLED
        assert handle.task.done()
        assert provider.active_calls == 0
        assert container.service.model.limiter.global_active == 0
        persisted = await container.store.get_run(run.id)
        assert persisted is not None and persisted.state is RunState.CANCELLED
        queued = []
        while not handle.queue.empty():
            queued.append(handle.queue.get_nowait())
            handle.queue.task_done()
        assert [event.type for event in queued[-2:]] == ["error", "done"]
        assert queued[-2].data["code"] == "RUN_CANCELLED"
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_full_queue_shutdown_is_bounded_and_releases_provider(
    settings: Settings,
    issue_token: Any,
) -> None:
    container, _principal, run, handle, provider = await start_flooded_run(
        settings,
        issue_token,
    )
    try:
        await asyncio.wait_for(container.service.shutdown(), timeout=1)
        assert handle.task.done()
        assert provider.active_calls == 0
        assert container.service.model.limiter.global_active == 0
        persisted = await container.store.get_run(run.id)
        assert persisted is not None and persisted.state is RunState.CANCELLED
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_full_queue_sse_disconnect_is_bounded_and_releases_provider(
    settings: Settings,
    issue_token: Any,
) -> None:
    container, principal, run, handle, provider = await start_flooded_run(
        settings,
        issue_token,
    )
    app = create_app(settings, container=container)
    route = next(
        route
        for route in app.routes
        if getattr(route, "path", "") == "/api/v1/agent/runs/{run_id}/events"
    )

    async def receive() -> dict[str, str]:
        return {"type": "http.request", "body": ""}

    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": f"/api/v1/agent/runs/{run.id}/events",
            "headers": [],
            "app": app,
        },
        receive=receive,
    )
    token = issue_token(IdentityKind.USER)
    credential = Credential(token=token, principal=principal)
    response = await route.endpoint(  # type: ignore[attr-defined]
        uuid.UUID(run.id),
        request,
        container,
        f"Bearer {credential.token}",
    )
    iterator = response.body_iterator
    try:
        _ = await asyncio.wait_for(anext(iterator), timeout=1)
        await asyncio.wait_for(iterator.aclose(), timeout=1)
        assert handle.task.done()
        assert provider.active_calls == 0
        assert container.service.model.limiter.global_active == 0
        persisted = await container.store.get_run(run.id)
        assert persisted is not None and persisted.state is RunState.CANCELLED
    finally:
        await container.close()
