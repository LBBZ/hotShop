from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from hotshop_agent.providers.base import (
    ModelChunk,
    ModelDelta,
    ModelPermanentError,
    ModelTemporaryError,
)
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.reliability import (
    CircuitBreaker,
    CircuitOpenError,
    ConcurrencyLimiter,
    ConcurrencyLimitError,
    ModelTimeoutError,
    ReliableModel,
)


class FlakyModel:
    name = "flaky"

    def __init__(self, failures: int, *, permanent: bool = False) -> None:
        self.failures = failures
        self.permanent = permanent
        self.calls = 0

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        self.calls += 1
        if self.calls <= self.failures:
            if self.permanent:
                raise ModelPermanentError
            raise ModelTemporaryError
        yield ModelDelta("ok")


def reliable(
    provider: object,
    *,
    timeout: float = 1,
    retries: int = 2,
    threshold: int = 3,
    global_limit: int = 2,
    user_limit: int = 1,
) -> ReliableModel:
    return ReliableModel(
        provider,  # type: ignore[arg-type]
        timeout_seconds=timeout,
        max_retries=retries,
        retry_base_seconds=0,
        breaker=CircuitBreaker(threshold, 60),
        limiter=ConcurrencyLimiter(global_limit, user_limit),
    )


async def collect(model: ReliableModel, owner: str, prompt: str) -> list[ModelChunk]:
    return [chunk async for chunk in model.stream(owner, prompt)]


@pytest.mark.asyncio
async def test_only_temporary_pre_stream_errors_are_retried() -> None:
    provider = FlakyModel(2)
    chunks = [chunk async for chunk in reliable(provider).stream("42", "prompt")]
    assert chunks == [ModelDelta("ok")]
    assert provider.calls == 3


@pytest.mark.asyncio
async def test_permanent_error_is_not_retried() -> None:
    provider = FlakyModel(1, permanent=True)
    with pytest.raises(ModelPermanentError):
        _ = [chunk async for chunk in reliable(provider).stream("42", "prompt")]
    assert provider.calls == 1


@pytest.mark.asyncio
async def test_model_call_timeout_stops_provider() -> None:
    provider = FakeModel(delay_seconds=0.1)
    with pytest.raises(ModelTimeoutError):
        _ = [chunk async for chunk in reliable(provider, timeout=0.01).stream("42", "prompt")]
    assert provider.active_calls == 0


@pytest.mark.asyncio
async def test_circuit_opens_after_threshold() -> None:
    provider = FlakyModel(10)
    model = reliable(provider, retries=0, threshold=2)
    for _ in range(2):
        with pytest.raises(ModelTemporaryError):
            _ = [chunk async for chunk in model.stream("42", "prompt")]
    with pytest.raises(CircuitOpenError):
        _ = [chunk async for chunk in model.stream("42", "prompt")]
    assert provider.calls == 2


class BlockingModel:
    name = "blocking"

    def __init__(self) -> None:
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        self.entered.set()
        await self.release.wait()
        yield ModelDelta("done")


class PartialFailureModel:
    name = "partial-failure"

    def __init__(self) -> None:
        self.calls = 0

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        self.calls += 1
        yield ModelDelta("already-sent")
        raise ModelTemporaryError


@pytest.mark.asyncio
async def test_temporary_error_after_delta_is_not_retried() -> None:
    provider = PartialFailureModel()
    model = reliable(provider, retries=3)
    received: list[ModelChunk] = []
    with pytest.raises(ModelTemporaryError):
        async for chunk in model.stream("42", "prompt"):
            received.append(chunk)
    assert received == [ModelDelta("already-sent")]
    assert provider.calls == 1


@pytest.mark.asyncio
async def test_per_user_concurrency_limit_rejects_and_releases() -> None:
    provider = BlockingModel()
    model = reliable(provider, global_limit=2, user_limit=1)
    first = asyncio.create_task(collect(model, "same-user", "first"))
    await provider.entered.wait()
    with pytest.raises(ConcurrencyLimitError):
        _ = [chunk async for chunk in model.stream("same-user", "second")]
    provider.release.set()
    assert await first == [ModelDelta("done")]
    assert model.limiter.global_active == 0


@pytest.mark.asyncio
async def test_global_concurrency_limit_applies_across_users() -> None:
    provider = BlockingModel()
    model = reliable(provider, global_limit=1, user_limit=1)
    first = asyncio.create_task(collect(model, "user-a", "first"))
    await provider.entered.wait()
    with pytest.raises(ConcurrencyLimitError):
        _ = [chunk async for chunk in model.stream("user-b", "second")]
    provider.release.set()
    await first
