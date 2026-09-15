"""Diagnostic of IR-02 against current Agent source; not a passing fix test.

Run with PYTHONPATH pointing to agent/src and the project's Python dependencies.
Exit zero means the reported baseline bug was reproduced. No external services.
"""

import asyncio
import time

from hotshop_agent.providers.base import ModelCapabilities, ModelDelta
from hotshop_agent.reliability import (
    CircuitBreaker,
    CircuitOpenError,
    ConcurrencyLimiter,
    ReliableModel,
)


class BlockingProvider:
    capabilities = ModelCapabilities("fake", "fake", True, True, "not_provided")

    def __init__(self):
        self.entered = asyncio.Event()

    async def stream(self, prompt):
        self.entered.set()
        await asyncio.Event().wait()
        yield ModelDelta("unused")


async def main():
    provider = BlockingProvider()
    breaker = CircuitBreaker(1, 1, failures=1, opened_at=time.monotonic() - 2)
    limiter = ConcurrencyLimiter(2, 1)
    model = ReliableModel(
        provider,
        timeout_seconds=60,
        max_retries=0,
        retry_base_seconds=0,
        breaker=breaker,
        limiter=limiter,
    )

    async def call():
        return [chunk async for chunk in model.stream("owner", "prompt")]

    task = asyncio.create_task(call())
    await asyncio.wait_for(provider.entered.wait(), timeout=5)
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    print("after_cancel:", breaker.state, breaker.half_open_in_flight, limiter.global_active)
    rejected = 0
    for attempt in range(3):
        try:
            breaker.before_call()
        except CircuitOpenError as exc:
            rejected += 1
            print("next_probe", attempt + 1, type(exc).__name__, str(exc))
    assert breaker.half_open_in_flight and limiter.global_active == 0 and rejected == 3


if __name__ == "__main__":
    asyncio.run(main())
