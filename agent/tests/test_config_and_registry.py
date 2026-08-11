from __future__ import annotations

import pytest
from pydantic import ValidationError

from hotshop_agent.config import Settings
from hotshop_agent.container import build_container
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.registry import ADMIN_TOOLS, USER_TOOLS


def test_fake_is_default_and_needs_no_qwen_api_key() -> None:
    settings = Settings(environment="test")
    container = build_container(settings)
    assert isinstance(container.service.model.provider, FakeModel)
    assert settings.qwen_api_key is None


def test_qwen_requires_api_key() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="test", provider="qwen")
    with pytest.raises(ValidationError):
        Settings(environment="test", provider="qwen", qwen_api_key="")


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
