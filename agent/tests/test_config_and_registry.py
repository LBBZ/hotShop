from __future__ import annotations

import pytest
from pydantic import SecretStr, ValidationError

from hotshop_agent.config import Settings
from hotshop_agent.container import build_container
from hotshop_agent.providers import MODEL_PROVIDER_REGISTRY, DeepSeekModel, QwenModel
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.registry import ADMIN_TOOLS, USER_TOOLS


def test_fake_is_default_and_needs_no_real_provider_key() -> None:
    settings = Settings(environment="test")
    container = build_container(settings)
    assert isinstance(container.service.model.provider, FakeModel)
    assert settings.model_provider == "fake"
    assert settings.deepseek_api_key is None
    assert settings.qwen_api_key is None


def test_only_active_real_provider_requires_its_own_key() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="test", model_provider="deepseek")
    with pytest.raises(ValidationError):
        Settings(environment="test", model_provider="deepseek", deepseek_api_key=SecretStr(""))
    deepseek = Settings(
        environment="test",
        model_provider="deepseek",
        deepseek_api_key=SecretStr("deepseek-test"),
    )
    assert deepseek.qwen_api_key is None

    with pytest.raises(ValidationError):
        Settings(environment="test", model_provider="qwen")
    with pytest.raises(ValidationError):
        Settings(environment="test", model_provider="qwen", qwen_api_key=SecretStr(""))
    qwen = Settings(environment="test", model_provider="qwen", qwen_api_key=SecretStr("qwen-test"))
    assert qwen.deepseek_api_key is None


def test_provider_registry_is_closed_read_only_and_factory_backed() -> None:
    assert tuple(MODEL_PROVIDER_REGISTRY) == ("fake", "deepseek", "qwen")
    with pytest.raises(TypeError):
        MODEL_PROVIDER_REGISTRY["attacker"] = lambda *_args: FakeModel()  # type: ignore[index]
    with pytest.raises(ValidationError):
        Settings(environment="test", model_provider="attacker")  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_real_provider_factory_selects_one_adapter_without_cross_keys() -> None:
    deepseek = build_container(
        Settings(
            environment="test",
            model_provider="deepseek",
            deepseek_api_key=SecretStr("deepseek-test"),
        )
    )
    qwen = build_container(
        Settings(environment="test", model_provider="qwen", qwen_api_key=SecretStr("qwen-test"))
    )
    deepseek_pro = build_container(
        Settings(
            environment="test",
            model_provider="deepseek",
            deepseek_model="deepseek-v4-pro",
            deepseek_api_key=SecretStr("deepseek-test"),
        )
    )
    try:
        assert isinstance(deepseek.service.model.provider, DeepSeekModel)
        assert isinstance(qwen.service.model.provider, QwenModel)
        assert deepseek.service.model.provider.model_name == "deepseek-v4-flash"
        assert qwen.service.model.provider.model_name == "qwen-plus"
        assert deepseek_pro.service.model.provider.model_name == "deepseek-v4-pro"
    finally:
        await deepseek.close()
        await qwen.close()
        await deepseek_pro.close()


def test_production_requires_redis() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="production", state_backend="memory")


def test_redis_namespace_is_fixed() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="test", redis_key_prefix="other:")


def test_user_and_admin_tool_registries_are_fixed_and_static() -> None:
    assert USER_TOOLS.tools == (
        "search_products",
        "get_product",
        "compare_products",
        "list_my_orders",
        "list_my_reservations",
        "create_purchase_draft",
    )
    assert ADMIN_TOOLS.tools == (
        "read_statistics",
        "read_anomaly_summary",
        "create_configuration_draft",
    )
    with pytest.raises(LookupError):
        USER_TOOLS.resolve("shell")
    with pytest.raises(LookupError):
        ADMIN_TOOLS.resolve("dynamic-tool")
