from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class ModelDelta:
    text: str


@dataclass(frozen=True)
class ModelUsage:
    input_tokens: int
    output_tokens: int
    estimated_cost_usd: float


@dataclass(frozen=True)
class ModelToolCall:
    name: str
    arguments: dict[str, Any]


type ModelChunk = ModelDelta | ModelUsage | ModelToolCall


def parse_tool_call(value: str) -> ModelToolCall | None:
    try:
        parsed: Any = json.loads(value)
    except json.JSONDecodeError:
        return None
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"tool", "arguments"}
        or not isinstance(parsed.get("tool"), str)
        or not isinstance(parsed.get("arguments"), dict)
    ):
        return None
    return ModelToolCall(name=parsed["tool"], arguments=parsed["arguments"])


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
