from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

from hotshop_agent.providers.base import ModelChunk, ModelDelta, ModelUsage


class FakeModel:
    name = "fake"

    def __init__(self, delay_seconds: float = 0.0) -> None:
        self.delay_seconds = delay_seconds
        self.active_calls = 0
        self.completed_calls = 0

    async def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        self.active_calls += 1
        try:
            chunks = ("HotShop ", "FakeModel ", "response.")
            for chunk in chunks:
                if self.delay_seconds:
                    await asyncio.sleep(self.delay_seconds)
                yield ModelDelta(chunk)
            yield ModelUsage(
                input_tokens=max(1, len(prompt) // 4),
                output_tokens=6,
                estimated_cost_usd=0.0,
            )
            self.completed_calls += 1
        finally:
            self.active_calls -= 1
