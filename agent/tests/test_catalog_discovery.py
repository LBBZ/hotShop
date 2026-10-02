from typing import Any

import pytest
from pydantic import ValidationError

from hotshop_agent.domain import IdentityKind
from hotshop_agent.events import StreamEvent
from hotshop_agent.rag import RouteKind, route_query
from hotshop_agent.service import _catalog_results_event


@pytest.mark.parametrize(
    ("query", "tool", "arguments"),
    [
        ("推荐通勤耳机", "search_products", {"keyword": "耳机", "limit": 8}),
        ("搜索居家", "search_products", {"keyword": "居家", "limit": 8}),
        ("查看商品 913003", "get_product", {"productId": "913003"}),
        ("对比商品 913003 和 913004", "compare_products", {"productIds": ["913003", "913004"]}),
    ],
)
def test_discovery_uses_user_catalog_tools(
    query: str, tool: str, arguments: dict[str, Any]
) -> None:
    decision = route_query(query, IdentityKind.USER, "42")
    assert decision.kind == RouteKind.DYNAMIC_TOOL
    assert decision.tool_name == tool
    assert decision.tool_arguments == arguments
    assert route_query(query, IdentityKind.ADMINISTRATOR, "42").kind == RouteKind.FORBIDDEN


def test_catalog_cards_use_only_returned_ids_and_do_not_forward_tool_content() -> None:
    assert _catalog_results_event(
        "search_products",
        {
            "items": [{"productId": "913003", "name": "untrusted content", "price": "999.00"}],
            "token": "not-for-the-browser",
        },
    ) == {"mode": "search", "productIds": ["913003"]}
    assert _catalog_results_event("get_product", {"productId": "913004"}) == {
        "mode": "detail",
        "productIds": ["913004"],
    }
    assert _catalog_results_event("list_my_orders", {"items": [{"productId": "913003"}]}) is None
    assert _catalog_results_event("compare_products", {"products": [{"productId": "01"}]}) is None


@pytest.mark.parametrize(
    "data",
    [
        {"mode": [], "productIds": ["1"]},
        {"mode": "search", "productIds": [1]},
        {"mode": "search", "productIds": ["1", "1"]},
        {"mode": "search", "productIds": ["0"]},
        {"mode": "search", "productIds": ["1"], "price": "1.00"},
        {"mode": "search", "productIds": [str(i) for i in range(1, 22)]},
    ],
)
def test_catalog_event_rejects_malformed_ids_and_extra_fields(data: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        StreamEvent(type="catalog.results", sessionId="session", sequence=1, data=data)
