from __future__ import annotations

import asyncio
import time
from collections import defaultdict
from collections.abc import AsyncGenerator, AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from enum import StrEnum

from hotshop_agent.providers.base import (
    ModelChunk,
    ModelPermanentError,
    ModelProvider,
    ModelTemporaryError,
    validate_model_capabilities,
)


class ModelTimeoutError(Exception):
    pass


class CircuitOpenError(Exception):
    pass


class ConcurrencyLimitError(Exception):
    pass


class CircuitState(StrEnum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass(frozen=True, eq=False)
class CircuitAdmission:
    """One attempt's authority; identity owns a probe, generation owns its result."""

    generation: int
    probe: bool


@dataclass
class CircuitBreaker:
    failure_threshold: int
    recovery_seconds: float
    failures: int = 0
    opened_at: float | None = None
    _generation: int = field(default=0, init=False)
    _probe: CircuitAdmission | None = field(default=None, init=False)

    @property
    def half_open_in_flight(self) -> bool:
        return self._probe is not None

    @property
    def state(self) -> CircuitState:
        if self.opened_at is None:
            return CircuitState.CLOSED
        if time.monotonic() - self.opened_at >= self.recovery_seconds:
            return CircuitState.HALF_OPEN
        return CircuitState.OPEN

    def before_call(self) -> CircuitAdmission:
        state = self.state
        if state is CircuitState.OPEN:
            raise CircuitOpenError("model circuit is open")
        if state is CircuitState.HALF_OPEN:
            if self.half_open_in_flight:
                raise CircuitOpenError("model circuit is probing")
        admission = CircuitAdmission(self._generation, state is CircuitState.HALF_OPEN)
        if admission.probe:
            self._probe = admission
        return admission

    def _owns_result(self, admission: CircuitAdmission) -> bool:
        return admission.generation == self._generation and (
            not admission.probe or self._probe is admission
        )

    def release(self, admission: CircuitAdmission) -> None:
        # Cancellation/close is neutral; only this owner can release its lease.
        if self._probe is admission:
            self._probe = None

    def success(self, admission: CircuitAdmission) -> None:
        if not self._owns_result(admission):
            return
        self.failures = 0
        self.opened_at = None
        if admission.probe:
            self._generation += 1
        self.release(admission)

    def failure(self, admission: CircuitAdmission) -> None:
        if not self._owns_result(admission):
            return
        self.failures += 1
        self.release(admission)
        if self.failures >= self.failure_threshold:
            self.opened_at = time.monotonic()
            self._generation += 1


class ConcurrencyLimiter:
    def __init__(self, global_limit: int, user_limit: int) -> None:
        self._global_limit = global_limit
        self._user_limit = user_limit
        self._global_active = 0
        self._user_active: defaultdict[str, int] = defaultdict(int)
        self._lock = asyncio.Lock()

    @property
    def global_active(self) -> int:
        return self._global_active

    @asynccontextmanager
    async def acquire(self, owner_id: str) -> AsyncIterator[None]:
        async with self._lock:
            if (
                self._global_active >= self._global_limit
                or self._user_active[owner_id] >= self._user_limit
            ):
                raise ConcurrencyLimitError("model concurrency limit reached")
            self._global_active += 1
            self._user_active[owner_id] += 1
        try:
            yield
        finally:
            async with self._lock:
                self._global_active -= 1
                self._user_active[owner_id] -= 1
                if self._user_active[owner_id] == 0:
                    del self._user_active[owner_id]


class ReliableModel:
    def __init__(
        self,
        provider: ModelProvider,
        *,
        timeout_seconds: float,
        max_retries: int,
        retry_base_seconds: float,
        breaker: CircuitBreaker,
        limiter: ConcurrencyLimiter,
    ) -> None:
        validate_model_capabilities(provider.capabilities)
        self.provider = provider
        self.timeout_seconds = timeout_seconds
        self.max_retries = max_retries
        self.retry_base_seconds = retry_base_seconds
        self.breaker = breaker
        self.limiter = limiter

    async def stream(
        self,
        owner_id: str,
        prompt: str,
    ) -> AsyncGenerator[ModelChunk, None]:
        async with self.limiter.acquire(owner_id):
            emitted = False
            for attempt in range(self.max_retries + 1):
                admission = self.breaker.before_call()
                try:
                    async with asyncio.timeout(self.timeout_seconds):
                        source = self.provider.stream(prompt)
                        primary_error: BaseException | None = None
                        try:
                            async for chunk in source:
                                emitted = True
                                yield chunk
                        except BaseException as exc:
                            primary_error = exc
                            raise
                        finally:
                            close = getattr(source, "aclose", None)
                            if close is not None:
                                try:
                                    await close()
                                except Exception:
                                    # Cleanup must not replace cancellation, generator
                                    # close, timeout, or the original provider failure.
                                    if primary_error is None:
                                        raise
                    self.breaker.success(admission)
                    return
                except TimeoutError as exc:
                    self.breaker.failure(admission)
                    raise ModelTimeoutError("model call timed out") from exc
                except ModelPermanentError:
                    self.breaker.failure(admission)
                    raise
                except ModelTemporaryError:
                    self.breaker.failure(admission)
                    if emitted or attempt >= self.max_retries:
                        raise
                except Exception:
                    self.breaker.failure(admission)
                    raise
                finally:
                    self.breaker.release(admission)
                await asyncio.sleep(self.retry_base_seconds * (2**attempt))
