from __future__ import annotations

import pytest

from hotshop_agent.domain import IdentityKind
from hotshop_agent.knowledge import DocumentType
from hotshop_agent.rag import RouteKind, route_query


@pytest.mark.parametrize(
    ("question", "document_type"),
    [
        ("看看售后政策", DocumentType.AFTER_SALES_POLICY),
        ("How do I request a return?", DocumentType.AFTER_SALES_POLICY),
        ("Where can I request a refund?", DocumentType.AFTER_SALES_POLICY),
        ("How can I secure my account?", DocumentType.FAQ),
        ("How do I sign in again?", DocumentType.FAQ),
        ("Where is the help center?", DocumentType.FAQ),
        ("秒杀预约提交成功就算买到了吗？", DocumentType.CAMPAIGN_RULE),
        ("What are the flash sale rules?", DocumentType.CAMPAIGN_RULE),
    ],
)
def test_help_questions_reach_their_knowledge_type(
    question: str, document_type: DocumentType
) -> None:
    route = route_query(question, IdentityKind.USER, "42")
    assert route.kind is RouteKind.STATIC
    assert route.document_types == (document_type,)
    assert route.tool_name is None


@pytest.mark.parametrize(
    ("question", "tool"),
    [
        ("商品 913001 价格多少，看看售后政策", "get_product"),
        ("看看商品 913001 的库存和售后政策", "get_product"),
        ("How much does product 913001 cost? Return policy?", "get_product"),
        ("我的订单状态是什么？售后规则也说一下", "list_my_orders"),
        ("推荐通勤耳机", "search_products"),
    ],
)
def test_live_facts_keep_priority_over_static_help(question: str, tool: str) -> None:
    route = route_query(question, IdentityKind.USER, "42")
    assert route.kind is RouteKind.DYNAMIC_TOOL
    assert route.tool_name == tool
