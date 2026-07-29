from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ModelDelta:
    text: str


@dataclass(frozen=True)
class ModelUsage:
    input_tokens: int
    output_tokens: int
    estimated_cost_usd: float


type ModelChunk = ModelDelta | ModelUsage


class ModelError(Exception):
    pass


class ModelTemporaryError(ModelError):
    pass


class ModelPermanentError(ModelError):
    pass


class ModelProvider(Protocol):
    name: str

    def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        """Stream final answer deltas and one usage item."""
        ...
