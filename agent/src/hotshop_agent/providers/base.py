from __future__ import annotations

import json
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Literal, Protocol

ReasoningContentHandling = Literal["not_provided", "ignored"]
ALLOWED_MODEL_METADATA = frozenset(
    {
        ("fake", "fake"),
        ("deepseek", "deepseek-v4-flash"),
        ("deepseek", "deepseek-v4-pro"),
        ("qwen", "qwen-plus"),
        ("qwen", "qwen-max"),
    }
)


@dataclass(frozen=True)
class ModelCapabilities:
    provider_name: str
    model_name: str
    streaming: bool
    custom_json_tool_selection: bool
    reasoning_content_handling: ReasoningContentHandling


def validate_model_capabilities(capabilities: ModelCapabilities) -> None:
    if (capabilities.provider_name, capabilities.model_name) not in ALLOWED_MODEL_METADATA:
        raise ValueError("model provider metadata is not allowlisted")
    if not capabilities.streaming or not capabilities.custom_json_tool_selection:
        raise ValueError("model provider lacks required capabilities")


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
    model_name: str
    capabilities: ModelCapabilities

    def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        """Stream final answer deltas and one usage item."""
        ...
