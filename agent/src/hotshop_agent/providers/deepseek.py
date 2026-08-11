from __future__ import annotations

from collections.abc import AsyncIterator

import httpx

from hotshop_agent.providers.base import ModelCapabilities, ModelChunk
from hotshop_agent.providers.openai_compatible import OpenAICompatibleChatTransport


class DeepSeekModel:
    name = "deepseek"

    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        base_url: str = "https://api.deepseek.com",
        api_key: str,
        model: str = "deepseek-v4-flash",
        input_cost_per_million_usd: float = 0.0,
        output_cost_per_million_usd: float = 0.0,
    ) -> None:
        self.model_name = model
        self.capabilities = ModelCapabilities(
            provider_name=self.name,
            model_name=model,
            streaming=True,
            custom_json_tool_selection=True,
            reasoning_content_handling="ignored",
        )
        self._transport = OpenAICompatibleChatTransport(
            client,
            base_url=base_url,
            api_key=api_key,
            model=model,
            request_extensions={"thinking": {"type": "disabled"}},
            input_cost_per_million_usd=input_cost_per_million_usd,
            output_cost_per_million_usd=output_cost_per_million_usd,
        )

    def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        return self._transport.stream(prompt)
