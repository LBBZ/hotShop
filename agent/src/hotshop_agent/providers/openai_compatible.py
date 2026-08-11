from __future__ import annotations

import json
from collections.abc import AsyncIterator, Mapping
from typing import Any

import httpx

from hotshop_agent.providers.base import (
    ModelChunk,
    ModelDelta,
    ModelPermanentError,
    ModelTemporaryError,
    ModelToolCall,
    ModelUsage,
    parse_tool_call,
)

MAX_RESPONSE_BYTES = 1_048_576
MAX_TOOL_CALL_BYTES = 65_536


class OpenAICompatibleChatTransport:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        base_url: str,
        api_key: str,
        model: str,
        request_extensions: Mapping[str, Any] | None = None,
        input_cost_per_million_usd: float = 0.0,
        output_cost_per_million_usd: float = 0.0,
    ) -> None:
        if not api_key.strip():
            raise ValueError("model provider key is required")
        self._client = client
        self._url = f"{base_url.rstrip('/')}/chat/completions"
        self._api_key = api_key
        self._model = model
        self._extensions = dict(request_extensions or {})
        self._input_cost = input_cost_per_million_usd
        self._output_cost = output_cost_per_million_usd

    async def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        payload: dict[str, Any] = {
            "model": self._model,
            "stream": True,
            "stream_options": {"include_usage": True},
            "messages": [{"role": "user", "content": prompt}],
            **self._extensions,
        }
        buffered: list[str] | None = None
        buffered_bytes = 0
        response_bytes = 0
        tool_candidate_possible = True
        deferred_usage: ModelUsage | None = None
        try:
            async with self._client.stream(
                "POST",
                self._url,
                headers={"Authorization": f"Bearer {self._api_key}"},
                json=payload,
            ) as response:
                if response.status_code in {408, 429} or response.status_code >= 500:
                    raise ModelTemporaryError("model provider is temporarily unavailable")
                if response.status_code >= 400:
                    raise ModelPermanentError("model provider request was rejected")
                async for line in response.aiter_lines():
                    response_bytes += len(line.encode("utf-8"))
                    if response_bytes > MAX_RESPONSE_BYTES:
                        raise ModelPermanentError("model provider response exceeded the limit")
                    if not line.startswith("data: "):
                        continue
                    raw = line[6:]
                    if raw == "[DONE]":
                        break
                    event = json.loads(raw)
                    choices = event.get("choices") or []
                    if choices:
                        delta = choices[0].get("delta", {})
                        text = delta.get("content")
                        # reasoning_content is intentionally never read or retained.
                        if isinstance(text, str) and text:
                            if tool_candidate_possible and buffered is None:
                                if text.startswith("{"):
                                    buffered = []
                                else:
                                    tool_candidate_possible = False
                            if buffered is not None:
                                buffered.append(text)
                                buffered_bytes += len(text.encode("utf-8"))
                                if buffered_bytes > MAX_TOOL_CALL_BYTES:
                                    yield ModelDelta("".join(buffered))
                                    buffered = None
                                    tool_candidate_possible = False
                            else:
                                yield ModelDelta(text)
                    usage = event.get("usage")
                    if isinstance(usage, dict):
                        input_tokens = int(usage.get("prompt_tokens", 0))
                        output_tokens = int(usage.get("completion_tokens", 0))
                        item = ModelUsage(
                            input_tokens=input_tokens,
                            output_tokens=output_tokens,
                            estimated_cost_usd=(
                                input_tokens * self._input_cost + output_tokens * self._output_cost
                            )
                            / 1_000_000,
                        )
                        if buffered is not None:
                            deferred_usage = item
                        else:
                            yield item
                if buffered is not None:
                    completed = "".join(buffered)
                    tool_call: ModelToolCall | None = parse_tool_call(completed)
                    yield tool_call if tool_call is not None else ModelDelta(completed)
                if deferred_usage is not None:
                    yield deferred_usage
        except (ModelPermanentError, ModelTemporaryError):
            raise
        except (httpx.TransportError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise ModelTemporaryError("model provider transport failed") from exc
