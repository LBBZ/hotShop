from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator
from types import SimpleNamespace

import pytest

from hotshop_agent.providers.base import (
    ModelCapabilities,
    ModelChunk,
    ModelDelta,
    ModelPermanentError,
    ModelTemporaryError,
)
from hotshop_agent.reliability import (
    CircuitBreaker,
    CircuitOpenError,
    CircuitState,
    ConcurrencyLimiter,
    ModelTimeoutError,
    ReliableModel,
)


class ControlledProvider:
    name = "controlled"
    model_name = "controlled"
    capabilities = ModelCapabilities("fake", "fake", True, True, "not_provided")

    def __init__(self) -> None:
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.closed = asyncio.Event()
        self.error: Exception | None = None

    async def stream(self, prompt: str) -> AsyncGenerator[ModelChunk, None]:
        try:
            self.entered.set()
            if prompt == "partial":
                yield ModelDelta("partial")
            await self.release.wait()
            if self.error is not None:
                raise self.error
            yield ModelDelta("done")
        finally:
            self.closed.set()


def model_for(provider: ControlledProvider, breaker: CircuitBreaker) -> ReliableModel:
    return ReliableModel(
        provider,
        timeout_seconds=60,
        max_retries=0,
        retry_base_seconds=0,
        breaker=breaker,
        limiter=ConcurrencyLimiter(8, 8),
    )


async def collect(model: ReliableModel, prompt: str = "wait") -> list[ModelChunk]:
    return [chunk async for chunk in model.stream("owner", prompt)]


@pytest.fixture
def clock(monkeypatch: pytest.MonkeyPatch) -> list[float]:
    now = [100.0]
    monkeypatch.setattr("hotshop_agent.reliability.time", SimpleNamespace(monotonic=lambda: now[0]))
    return now


@pytest.mark.asyncio
async def test_cancelled_half_open_probe_releases_ownership(clock: list[float]) -> None:
    provider = ControlledProvider()
    breaker = CircuitBreaker(1, 10, failures=1, opened_at=80)
    model = model_for(provider, breaker)
    task = asyncio.create_task(collect(model))
    await provider.entered.wait()
    with pytest.raises(CircuitOpenError):
        await collect(model)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert model.limiter.global_active == 0
    assert provider.closed.is_set()
    assert not breaker.half_open_in_flight
    provider.release.set()
    assert await collect(model) == [ModelDelta("done")]
    assert breaker.state.value == "closed"


@pytest.mark.asyncio
async def test_closed_half_open_generator_releases_provider_and_probe(clock: list[float]) -> None:
    provider = ControlledProvider()
    breaker = CircuitBreaker(1, 10, failures=1, opened_at=80)
    model = model_for(provider, breaker)
    stream = model.stream("owner", "partial")
    assert await anext(stream) == ModelDelta("partial")
    await stream.aclose()
    assert model.limiter.global_active == 0
    assert provider.closed.is_set()
    assert not breaker.half_open_in_flight
    provider.release.set()
    assert await collect(model) == [ModelDelta("done")]


@pytest.mark.asyncio
@pytest.mark.parametrize("old_outcome", ["success", "failure", "cancel"])
async def test_old_closed_request_cannot_change_new_probe(
    clock: list[float], old_outcome: str
) -> None:
    provider = ControlledProvider()
    breaker = CircuitBreaker(1, 10)
    model = model_for(provider, breaker)
    old = asyncio.create_task(collect(model))
    await provider.entered.wait()
    # Another admitted request opens a new circuit cycle.
    ticket = breaker.before_call()
    breaker.failure(ticket)
    clock[0] += 11
    new_probe = breaker.before_call()
    if old_outcome == "cancel":
        old.cancel()
        with pytest.raises(asyncio.CancelledError):
            await old
    else:
        if old_outcome == "failure":
            provider.error = RuntimeError("old provider failure")
            provider.release.set()
            with pytest.raises(RuntimeError, match="old provider failure"):
                await old
        else:
            provider.release.set()
            await old
    assert breaker.state is CircuitState.HALF_OPEN
    assert breaker.half_open_in_flight
    with pytest.raises(CircuitOpenError):
        breaker.before_call()
    breaker.success(new_probe)
    assert breaker.state.value == "closed"
    assert model.limiter.global_active == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", ["success", "temporary", "permanent", "timeout", "unexpected"])
async def test_half_open_outcomes_and_recovery(clock: list[float], outcome: str) -> None:
    provider = ControlledProvider()
    breaker = CircuitBreaker(1, 10, failures=1, opened_at=80)
    model = model_for(provider, breaker)
    errors = {
        "temporary": ModelTemporaryError,
        "permanent": ModelPermanentError,
        "unexpected": RuntimeError,
        "timeout": ModelTimeoutError,
    }
    if outcome == "timeout":
        # Zero timeout is scheduled on the event loop's next turn, without sleeps.
        model.timeout_seconds = 0
    else:
        provider.release.set()
        if outcome != "success":
            provider.error = errors[outcome]("provider failed")
    if outcome == "success":
        assert await collect(model) == [ModelDelta("done")]
        assert breaker.state.value == "closed"
    else:
        with pytest.raises(errors[outcome]):
            await collect(model)
        assert breaker.state is CircuitState.OPEN
        clock[0] += 11
    assert provider.closed.is_set()
    assert not breaker.half_open_in_flight
    assert model.limiter.global_active == 0
    provider.error = None
    provider.release.set()
    model.timeout_seconds = 60
    assert await collect(model) == [ModelDelta("done")]
    assert breaker.state.value == "closed"


def test_old_probe_cannot_release_or_finish_replacement_in_same_generation(
    clock: list[float],
) -> None:
    breaker = CircuitBreaker(1, 10, failures=1, opened_at=80)
    old = breaker.before_call()
    breaker.release(old)
    replacement = breaker.before_call()
    breaker.release(old)
    breaker.success(old)
    breaker.failure(old)
    assert breaker.half_open_in_flight
    assert breaker.state is CircuitState.HALF_OPEN
    breaker.failure(replacement)
    opened_at = breaker.opened_at
    clock[0] += 11
    newest = breaker.before_call()
    breaker.success(replacement)
    breaker.failure(replacement)
    breaker.release(replacement)
    assert breaker.opened_at == opened_at
    assert breaker.half_open_in_flight
    breaker.success(newest)
    assert breaker.state.value == "closed"


@pytest.mark.asyncio
async def test_retry_rechecks_circuit_instead_of_bypassing_open_cycle(clock: list[float]) -> None:
    provider = ControlledProvider()
    provider.error = ModelTemporaryError("unavailable")
    provider.release.set()
    breaker = CircuitBreaker(1, 10)
    model = model_for(provider, breaker)
    model.max_retries = 2
    with pytest.raises(CircuitOpenError):
        await collect(model)
    assert breaker.failures == 1
    assert model.limiter.global_active == 0


class BrokenCleanupIterator:
    def __init__(self, partial: bool) -> None:
        self.entered = asyncio.Event()
        self.partial = partial
        self.close_calls = 0
        self.complete = False
        self.fail = False

    def __aiter__(self) -> BrokenCleanupIterator:
        return self

    async def __anext__(self) -> ModelChunk:
        self.entered.set()
        if self.fail:
            raise ModelPermanentError("original failure")
        if self.complete:
            raise StopAsyncIteration
        if self.partial:
            self.partial = False
            return ModelDelta("partial")
        await asyncio.Event().wait()
        raise StopAsyncIteration

    async def aclose(self) -> None:
        self.close_calls += 1
        raise RuntimeError("cleanup failed")


class BrokenCleanupProvider:
    name = "broken-cleanup"
    model_name = "broken-cleanup"
    capabilities = ControlledProvider.capabilities

    def __init__(self, partial: bool) -> None:
        self.iterator = BrokenCleanupIterator(partial)

    def stream(self, prompt: str) -> BrokenCleanupIterator:
        return self.iterator


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", ["cancel", "close", "timeout", "success", "failure"])
async def test_cleanup_failure_preserves_primary_control_flow(
    clock: list[float], outcome: str
) -> None:
    provider = BrokenCleanupProvider(partial=outcome == "close")
    provider.iterator.complete = outcome == "success"
    provider.iterator.fail = outcome == "failure"
    breaker = CircuitBreaker(1, 10, failures=1, opened_at=80)
    model = ReliableModel(
        provider,
        timeout_seconds=0 if outcome == "timeout" else 60,
        max_retries=0,
        retry_base_seconds=0,
        breaker=breaker,
        limiter=ConcurrencyLimiter(2, 2),
    )
    if outcome == "close":
        stream = model.stream("owner", "partial")
        assert await anext(stream) == ModelDelta("partial")
        await stream.aclose()
    elif outcome == "cancel":
        task = asyncio.create_task(collect(model))
        await provider.iterator.entered.wait()
        task.cancel("user cancelled")
        with pytest.raises(asyncio.CancelledError, match="user cancelled"):
            await task
    elif outcome == "timeout":
        with pytest.raises(ModelTimeoutError):
            await collect(model)
    elif outcome == "success":
        with pytest.raises(RuntimeError, match="cleanup failed"):
            await collect(model)
    else:
        with pytest.raises(ModelPermanentError, match="original failure"):
            await collect(model)
    assert provider.iterator.close_calls == 1
    assert not breaker.half_open_in_flight
    assert model.limiter.global_active == 0
    failed = outcome in {"timeout", "success", "failure"}
    assert breaker.state is (CircuitState.OPEN if failed else CircuitState.HALF_OPEN)
    if failed:
        clock[0] += 11
    replacement = breaker.before_call()
    breaker.success(replacement)
    assert breaker.state.value == "closed"
