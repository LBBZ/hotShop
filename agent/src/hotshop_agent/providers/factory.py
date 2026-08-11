from __future__ import annotations

from collections.abc import Callable, Mapping
from types import MappingProxyType

import httpx

from hotshop_agent.config import Settings
from hotshop_agent.providers.base import ModelProvider
from hotshop_agent.providers.deepseek import DeepSeekModel
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.providers.qwen import QwenModel

ProviderBuilder = Callable[[Settings, httpx.AsyncClient], ModelProvider]


def _fake(_settings: Settings, _client: httpx.AsyncClient) -> ModelProvider:
    return FakeModel()


def _deepseek(settings: Settings, client: httpx.AsyncClient) -> ModelProvider:
    assert settings.deepseek_api_key is not None
    return DeepSeekModel(
        client,
        base_url=settings.deepseek_base_url,
        api_key=settings.deepseek_api_key.get_secret_value(),
        model=settings.deepseek_model,
        input_cost_per_million_usd=settings.deepseek_input_cost_per_million_usd,
        output_cost_per_million_usd=settings.deepseek_output_cost_per_million_usd,
    )


def _qwen(settings: Settings, client: httpx.AsyncClient) -> ModelProvider:
    assert settings.qwen_api_key is not None
    return QwenModel(
        client,
        base_url=settings.qwen_base_url,
        api_key=settings.qwen_api_key.get_secret_value(),
        model=settings.qwen_model,
        input_cost_per_million_usd=settings.qwen_input_cost_per_million_usd,
        output_cost_per_million_usd=settings.qwen_output_cost_per_million_usd,
    )


MODEL_PROVIDER_REGISTRY: Mapping[str, ProviderBuilder] = MappingProxyType(
    {"fake": _fake, "deepseek": _deepseek, "qwen": _qwen}
)


def build_model_provider(settings: Settings, client: httpx.AsyncClient) -> ModelProvider:
    try:
        builder = MODEL_PROVIDER_REGISTRY[settings.model_provider]
    except KeyError as exc:
        raise ValueError("unsupported model provider") from exc
    return builder(settings, client)
