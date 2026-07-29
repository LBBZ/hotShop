from __future__ import annotations

import json
from collections.abc import AsyncIterator

import httpx

from hotshop_agent.providers.base import (
    ModelChunk,
    ModelDelta,
    ModelPermanentError,
    ModelTemporaryError,
    ModelUsage,
)


class QwenModel:
    name = "qwen"

    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        base_url: str,
        api_key: str,
        model: str,
        input_cost_per_million_usd: float = 0.0,
        output_cost_per_million_usd: float = 0.0,
    ) -> None:
        self._client = client
        self._url = f"{base_url}/chat/completions"
        self._api_key = api_key
        self._model = model
        self._input_cost = input_cost_per_million_usd
        self._output_cost = output_cost_per_million_usd

    async def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        headers = {"Authorization": f"Bearer {self._api_key}"}
        payload = {
            "model": self._model,
            "stream": True,
            "stream_options": {"include_usage": True},
            "messages": [{"role": "user", "content": prompt}],
        }
        try:
            async with self._client.stream(
                "POST",
                self._url,
                headers=headers,
                json=payload,
            ) as response:
                if response.status_code in {408, 429, 500, 502, 503, 504}:
                    raise ModelTemporaryError("Qwen is temporarily unavailable")
                if response.status_code >= 400:
                    raise ModelPermanentError("Qwen request was rejected")
                async for line in response.aiter_lines():
                    if not line.startswith("data: "):
                        continue
                    raw = line[6:]
                    if raw == "[DONE]":
                        break
                    event = json.loads(raw)
                    choices = event.get("choices") or []
                    if choices:
                        text = choices[0].get("delta", {}).get("content")
                        if isinstance(text, str) and text:
                            yield ModelDelta(text)
                    usage = event.get("usage")
                    if isinstance(usage, dict):
                        input_tokens = int(usage.get("prompt_tokens", 0))
                        output_tokens = int(usage.get("completion_tokens", 0))
                        yield ModelUsage(
                            input_tokens=input_tokens,
                            output_tokens=output_tokens,
                            estimated_cost_usd=(
                                input_tokens * self._input_cost + output_tokens * self._output_cost
                            )
                            / 1_000_000,
                        )
        except ModelPermanentError:
            raise
        except (httpx.TransportError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise ModelTemporaryError("Qwen transport failed") from exc
