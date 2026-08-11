from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from hotshop_agent.domain import Credential, IdentityKind
from hotshop_agent.registry import (
    ADMIN_TOOL_SPECS,
    USER_TOOL_SPECS,
    ToolContext,
    ToolRegistry,
)
from hotshop_agent.security import JwtVerifier


def context(token: str, verifier: JwtVerifier, kind: IdentityKind) -> ToolContext:
    principal = verifier.verify(token, kind)
    return ToolContext(
        credential=Credential(token=token, principal=principal),
        owner_id=principal.subject_user_id,
        request_id="request-16",
        trace_id="a" * 32,
    )


def user_registry(
    settings: Any,
    transport: httpx.AsyncBaseTransport,
) -> tuple[ToolRegistry, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=transport)
    return (
        ToolRegistry(
            IdentityKind.USER,
            USER_TOOL_SPECS,
            client=client,
            verifier=JwtVerifier(settings),
            base_url="http://portal.test",
        ),
        client,
    )


def record_request(
    request: httpx.Request,
    calls: list[httpx.Request],
    *,
    json_body: dict[str, Any] | None = None,
) -> httpx.Response:
    calls.append(request)
    if json_body is None:
        return httpx.Response(200)
    return httpx.Response(200, json=json_body)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tool", "arguments", "method", "path"),
    (
        ("search_products", {"keyword": "tea", "limit": 5}, "GET", "/agent/api/v1/tools/products"),
        ("get_product", {"productId": "12"}, "GET", "/agent/api/v1/tools/products/12"),
        (
            "compare_products",
            {"productIds": ["1", "2"]},
            "POST",
            "/agent/api/v1/tools/product-comparisons",
        ),
        ("list_my_orders", {"limit": 10}, "GET", "/agent/api/v1/tools/orders"),
        ("list_my_reservations", {"limit": 10}, "GET", "/agent/api/v1/tools/reservations"),
        (
            "create_purchase_draft",
            {"items": [{"productId": "7", "quantity": 2}]},
            "POST",
            "/agent/api/v1/tools/purchase-drafts",
        ),
    ),
)
async def test_all_user_tools_use_only_fixed_java_routes(
    settings: Any,
    issue_token: Any,
    tool: str,
    arguments: dict[str, Any],
    method: str,
    path: str,
) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"items": [{"id": "safe"}]})

    token = issue_token(
        IdentityKind.DELEGATION,
        claim_overrides={
            "scope": ("catalog:read orders:self:read reservations:self:read purchase-drafts:create")
        },
    )
    registry, client = user_registry(settings, httpx.MockTransport(handler))
    try:
        result = await registry.execute(
            tool,
            arguments,
            context(token, JwtVerifier(settings), IdentityKind.DELEGATION),
        )
    finally:
        await client.aclose()
    assert result.outcome == "SUCCESS"
    assert len(calls) == 1
    assert calls[0].method == method
    assert calls[0].url.path == path
    assert calls[0].headers["x-request-id"] == "request-16"
    assert calls[0].headers["traceparent"].startswith(f"00-{'a' * 32}-")
    assert calls[0].headers["traceparent"].endswith("-01")


@pytest.mark.asyncio
async def test_unknown_dynamic_and_high_risk_tools_never_reach_http(
    settings: Any,
    issue_token: Any,
) -> None:
    calls: list[httpx.Request] = []
    registry, client = user_registry(
        settings,
        httpx.MockTransport(lambda request: record_request(request, calls)),
    )
    token = issue_token(
        IdentityKind.DELEGATION,
        claim_overrides={
            "scope": ("catalog:read orders:self:read reservations:self:read purchase-drafts:create")
        },
    )
    tool_context = context(token, JwtVerifier(settings), IdentityKind.DELEGATION)
    try:
        for name, arguments in (
            ("shell", {"command": "whoami"}),
            ("sql", {"query": "select * from user"}),
            ("fetch_url", {"url": "https://example.test"}),
            ("refund_order", {"orderId": "1"}),
            ("tools/" + "search_products", {}),
        ):
            result = await registry.execute(name, arguments, tool_context)
            assert result.error is not None
            assert result.error.code == "TOOL_NOT_ALLOWED"
    finally:
        await client.aclose()
    assert calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tool", "arguments"),
    (
        ("get_product", {"productId": 1}),
        ("get_product", {"productId": "0"}),
        ("get_product", {"productId": "1", "url": "https://example.test"}),
        ("compare_products", {"productIds": ["1"]}),
        ("compare_products", {"productIds": [str(value) for value in range(1, 12)]}),
        ("compare_products", {"productIds": ["1", "1"]}),
        ("list_my_orders", {"limit": "20"}),
        ("list_my_orders", {"limit": 20, "userId": "99"}),
        ("create_purchase_draft", {"items": [{"productId": "1", "quantity": True}]}),
    ),
)
async def test_strict_schema_rejects_extra_type_confusion_limits_and_illegal_ids(
    settings: Any,
    issue_token: Any,
    tool: str,
    arguments: dict[str, Any],
) -> None:
    calls: list[httpx.Request] = []
    registry, client = user_registry(
        settings,
        httpx.MockTransport(lambda request: record_request(request, calls)),
    )
    token = issue_token(
        IdentityKind.DELEGATION,
        claim_overrides={
            "scope": ("catalog:read orders:self:read reservations:self:read purchase-drafts:create")
        },
    )
    try:
        result = await registry.execute(
            tool,
            arguments,
            context(token, JwtVerifier(settings), IdentityKind.DELEGATION),
        )
    finally:
        await client.aclose()
    assert result.error is not None
    assert result.error.code == "TOOL_ARGUMENT_INVALID"
    assert calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "claim_overrides",
    (
        {"scope": "orders:self:read"},
        {"iss": "https://wrong.local"},
        {"aud": "wrong-audience"},
        {"azp": "wrong-client"},
        {"token_use": "user_access"},
        {"sub": "99"},
    ),
)
async def test_each_call_revalidates_scope_audience_azp_type_and_delegated_user(
    settings: Any,
    issue_token: Any,
    claim_overrides: dict[str, Any],
) -> None:
    calls: list[httpx.Request] = []
    registry, client = user_registry(
        settings,
        httpx.MockTransport(lambda request: record_request(request, calls)),
    )
    valid_token = issue_token(IdentityKind.DELEGATION)
    principal = JwtVerifier(settings).verify(valid_token, IdentityKind.DELEGATION)
    invalid_token = issue_token(IdentityKind.DELEGATION, claim_overrides=claim_overrides)
    tool_context = ToolContext(
        credential=Credential(token=invalid_token, principal=principal),
        owner_id="42",
        request_id="request-16",
        trace_id="a" * 32,
    )
    try:
        result = await registry.execute("search_products", {}, tool_context)
    finally:
        await client.aclose()
    assert result.error is not None
    assert result.error.code == "TOOL_ACCESS_DENIED"
    assert calls == []


@pytest.mark.asyncio
async def test_administrator_token_cannot_call_user_tool(
    settings: Any,
    issue_token: Any,
) -> None:
    calls: list[httpx.Request] = []
    registry, client = user_registry(
        settings,
        httpx.MockTransport(lambda request: record_request(request, calls)),
    )
    admin = issue_token(IdentityKind.ADMINISTRATOR)
    try:
        result = await registry.execute(
            "search_products",
            {},
            context(admin, JwtVerifier(settings), IdentityKind.ADMINISTRATOR),
        )
    finally:
        await client.aclose()
    assert result.error is not None
    assert result.error.code == "TOOL_ACCESS_DENIED"
    assert calls == []


@pytest.mark.asyncio
async def test_tool_response_removes_sensitive_values(
    settings: Any,
    issue_token: Any,
) -> None:
    secret = "never-visible-confirmation-secret"  # noqa: S105
    registry, client = user_registry(
        settings,
        httpx.MockTransport(
            lambda _request: httpx.Response(
                200,
                json={"id": "1", "confirmationToken": secret, "token": secret},
            )
        ),
    )
    token = issue_token(IdentityKind.DELEGATION)
    try:
        result = await registry.execute(
            "get_product",
            {"productId": "1"},
            context(token, JwtVerifier(settings), IdentityKind.DELEGATION),
        )
    finally:
        await client.aclose()
    assert result.outcome == "SUCCESS"
    assert secret not in json.dumps(result.model_dump())


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tool", "arguments", "method", "path"),
    (
        ("read_statistics", {}, "GET", "/admin/api/v1/agent-tools/statistics"),
        ("read_anomaly_summary", {}, "GET", "/admin/api/v1/agent-tools/anomalies"),
        (
            "create_configuration_draft",
            {
                "configurationKey": "AGENT_TOOL_RESULT_LIMIT",
                "proposedValue": 10,
                "reason": "Bound model context",
            },
            "POST",
            "/admin/api/v1/agent-tools/configuration-drafts",
        ),
    ),
)
async def test_all_administrator_tools_are_low_risk_and_fixed(
    settings: Any,
    issue_token: Any,
    tool: str,
    arguments: dict[str, Any],
    method: str,
    path: str,
) -> None:
    calls: list[httpx.Request] = []
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: record_request(request, calls, json_body={"ok": True})
        )
    )
    registry = ToolRegistry(
        IdentityKind.ADMINISTRATOR,
        ADMIN_TOOL_SPECS,
        client=client,
        verifier=JwtVerifier(settings),
        base_url="http://admin.test",
    )
    token = issue_token(IdentityKind.ADMINISTRATOR)
    try:
        result = await registry.execute(
            tool,
            arguments,
            context(token, JwtVerifier(settings), IdentityKind.ADMINISTRATOR),
        )
    finally:
        await client.aclose()
    assert result.outcome == "SUCCESS"
    assert calls[0].method == method
    assert calls[0].url.path == path
