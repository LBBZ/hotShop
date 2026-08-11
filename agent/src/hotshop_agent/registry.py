from __future__ import annotations

import logging
import secrets
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Literal

import httpx
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictInt,
    StrictStr,
    ValidationError,
    field_validator,
    model_validator,
)

from hotshop_agent.domain import Credential, IdentityKind
from hotshop_agent.security import AuthenticationError, JwtVerifier


class ToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class SearchProductsInput(ToolInput):
    keyword: StrictStr | None = Field(default=None, min_length=1, max_length=200)
    limit: StrictInt = Field(default=10, ge=1, le=20)
    cursor: StrictStr | None = Field(default=None, pattern=r"^[1-9][0-9]{0,18}$")


class ProductIdInput(ToolInput):
    productId: StrictStr = Field(pattern=r"^[1-9][0-9]{0,18}$")


class CompareProductsInput(ToolInput):
    productIds: list[StrictStr] = Field(min_length=2, max_length=10)

    @field_validator("productIds")
    @classmethod
    def validate_product_ids(cls, value: list[str]) -> list[str]:
        if any(
            not item.isascii() or not item.isdecimal() or item.startswith("0") or len(item) > 19
            for item in value
        ) or len(set(value)) != len(value):
            raise ValueError("productIds must contain unique positive IDs")
        return value


class PageInput(ToolInput):
    limit: StrictInt = Field(default=10, ge=1, le=20)
    cursor: StrictStr | None = Field(default=None, min_length=1, max_length=512)


class ReservationPageInput(ToolInput):
    limit: StrictInt = Field(default=10, ge=1, le=20)


class PurchaseDraftItem(ToolInput):
    productId: StrictStr = Field(pattern=r"^[1-9][0-9]{0,18}$")
    quantity: StrictInt = Field(ge=1, le=100)


class PurchaseDraftInput(ToolInput):
    items: list[PurchaseDraftItem] = Field(min_length=1, max_length=20)

    @field_validator("items")
    @classmethod
    def validate_items(cls, value: list[PurchaseDraftItem]) -> list[PurchaseDraftItem]:
        if len({item.productId for item in value}) != len(value):
            raise ValueError("items must contain unique product IDs")
        return value


class StatisticsInput(ToolInput):
    pass


class AnomalySummaryInput(ToolInput):
    pass


class ConfigurationDraftInput(ToolInput):
    configurationKey: Literal[
        "AGENT_RESPONSE_STYLE",
        "AGENT_SUMMARY_WINDOW",
        "AGENT_TOOL_RESULT_LIMIT",
    ]
    proposedValue: StrictStr | StrictInt
    reason: StrictStr = Field(min_length=1, max_length=500)

    @model_validator(mode="after")
    def validate_key_value_pair(self) -> ConfigurationDraftInput:
        if self.configurationKey == "AGENT_RESPONSE_STYLE":
            if self.proposedValue not in {"CONCISE", "BALANCED", "DETAILED"}:
                raise ValueError("response style is not allowed")
        elif self.configurationKey == "AGENT_SUMMARY_WINDOW":
            if not isinstance(self.proposedValue, int) or not 1 <= self.proposedValue <= 24:
                raise ValueError("summary window is out of range")
        elif not isinstance(self.proposedValue, int) or not 1 <= self.proposedValue <= 100:
            raise ValueError("tool result limit is out of range")
        return self


class ToolError(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    code: str
    message: str
    retryable: bool = False


class ToolResult(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    tool: str
    resource_type: str
    outcome: Literal["SUCCESS", "FAILURE"]
    summary: str
    data: Any | None = None
    error: ToolError | None = None


@dataclass(frozen=True)
class ToolContext:
    credential: Credential
    owner_id: str
    request_id: str
    trace_id: str


@dataclass(frozen=True)
class ToolSpec:
    name: str
    boundary: IdentityKind
    scope: str | None
    method: Literal["GET", "POST"]
    path: str
    input_model: type[ToolInput]
    resource_type: str


class ToolRegistry:
    """A closed, code-owned registry. Model output can select only these entries."""

    def __init__(
        self,
        boundary: IdentityKind,
        specs: tuple[ToolSpec, ...],
        *,
        client: httpx.AsyncClient | None = None,
        verifier: JwtVerifier | None = None,
        base_url: str = "",
        timeout_seconds: float = 3.0,
    ) -> None:
        if any(spec.boundary is not boundary for spec in specs):
            raise ValueError("tool boundary mismatch")
        self.boundary = boundary
        raw_specs = {spec.name: spec for spec in specs}
        self._specs = MappingProxyType(raw_specs)
        if len(self._specs) != len(specs):
            raise ValueError("duplicate tool name")
        self._client = client
        self._verifier = verifier
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    @property
    def tools(self) -> tuple[str, ...]:
        return tuple(self._specs)

    def resolve(self, name: str) -> ToolSpec:
        spec = self._specs.get(name)
        if spec is None:
            raise LookupError("tool is not registered")
        return spec

    async def execute(
        self,
        name: str,
        arguments: object,
        context: ToolContext,
    ) -> ToolResult:
        try:
            spec = self.resolve(name)
        except LookupError:
            try:
                principal = self._verify_invocation(None, context)
                subject = principal.subject_user_id
            except AuthenticationError:
                subject = "unverified"
            self._log_rejected("unregistered", "unknown", subject, context)
            return self._failure(
                "unregistered", "unknown", "TOOL_NOT_ALLOWED", "Tool is not allowed"
            )
        try:
            principal = self._verify_invocation(spec.scope, context)
        except AuthenticationError:
            self._log_rejected(name, spec.resource_type, "unverified", context)
            return self._failure(
                name,
                spec.resource_type,
                "TOOL_ACCESS_DENIED",
                "Tool access is denied",
            )
        try:
            parsed = spec.input_model.model_validate(arguments)
        except ValidationError:
            self._log_rejected(name, spec.resource_type, principal.subject_user_id, context)
            return self._failure(
                name,
                spec.resource_type,
                "TOOL_ARGUMENT_INVALID",
                "Tool arguments are invalid",
            )
        if self._client is None or self._verifier is None or not self._base_url:
            self._log(spec, principal.subject_user_id, context, "FAILURE")
            return self._failure(
                name,
                spec.resource_type,
                "TOOL_UNAVAILABLE",
                "Tool backend is unavailable",
                retryable=True,
            )

        values = parsed.model_dump(exclude_none=True)
        path = spec.path
        if "{productId}" in path:
            path = path.replace("{productId}", str(values.pop("productId")))
        headers = {
            "Authorization": f"Bearer {context.credential.token}",
            "Accept": "application/json",
            "X-Request-ID": context.request_id,
            "traceparent": f"00-{context.trace_id}-{_span_id()}-01",
        }
        try:
            response = await self._client.request(
                spec.method,
                f"{self._base_url}{path}",
                params=values if spec.method == "GET" else None,
                json=values if spec.method == "POST" else None,
                headers=headers,
                timeout=self._timeout_seconds,
            )
        except httpx.HTTPError:
            self._log(spec, principal.subject_user_id, context, "FAILURE")
            return self._failure(
                name,
                spec.resource_type,
                "TOOL_BACKEND_UNAVAILABLE",
                "Tool backend is unavailable",
                retryable=True,
            )
        if response.status_code >= 400:
            code = "TOOL_ACCESS_DENIED" if response.status_code in {401, 403} else "TOOL_FAILED"
            self._log(spec, principal.subject_user_id, context, "FAILURE")
            return self._failure(name, spec.resource_type, code, "Tool request failed")
        if len(response.content) > 262_144:
            self._log(spec, principal.subject_user_id, context, "FAILURE")
            return self._failure(
                name,
                spec.resource_type,
                "TOOL_FAILED",
                "Tool response was invalid",
            )
        try:
            data = _safe_result(response.json())
        except (ValueError, TypeError):
            self._log(spec, principal.subject_user_id, context, "FAILURE")
            return self._failure(
                name, spec.resource_type, "TOOL_FAILED", "Tool response was invalid"
            )
        count = _result_count(data)
        summary = "Tool completed" if count is None else f"Tool completed with {count} item(s)"
        self._log(spec, principal.subject_user_id, context, "SUCCESS")
        return ToolResult(
            tool=name,
            resource_type=spec.resource_type,
            outcome="SUCCESS",
            summary=summary,
            data=data,
        )

    def _verify_invocation(self, required_scope: str | None, context: ToolContext) -> Any:
        if self._verifier is None:
            raise AuthenticationError
        expected = (
            IdentityKind.DELEGATION
            if self.boundary is IdentityKind.USER
            else IdentityKind.ADMINISTRATOR
        )
        principal = self._verifier.verify(context.credential.token, expected)
        if (
            principal != context.credential.principal
            or principal.subject_user_id != context.owner_id
            or (required_scope is not None and required_scope not in principal.scopes)
        ):
            raise AuthenticationError
        return principal

    @staticmethod
    def _failure(
        tool: str,
        resource_type: str,
        code: str,
        message: str,
        *,
        retryable: bool = False,
    ) -> ToolResult:
        return ToolResult(
            tool=tool[:64],
            resource_type=resource_type,
            outcome="FAILURE",
            summary=message,
            error=ToolError(code=code, message=message, retryable=retryable),
        )

    @staticmethod
    def _log(spec: ToolSpec, subject: str, context: ToolContext, outcome: str) -> None:
        logging.getLogger(__name__).info(
            "agent tool invocation",
            extra={
                "event": "agent.tool.invoked",
                "outcome": outcome,
                "errorType": "" if outcome == "SUCCESS" else "ToolError",
                "tool": spec.name,
                "resourceType": spec.resource_type,
                "subject": subject,
                "requestId": context.request_id,
                "traceId": context.trace_id,
                "identityKind": context.credential.principal.kind,
                "parameterSummary": "validated",
            },
        )

    @staticmethod
    def _log_rejected(
        tool: str,
        resource_type: str,
        subject: str,
        context: ToolContext,
    ) -> None:
        logging.getLogger(__name__).info(
            "agent tool invocation rejected",
            extra={
                "event": "agent.tool.invoked",
                "outcome": "FAILURE",
                "errorType": "ToolPolicyError",
                "tool": tool,
                "resourceType": resource_type,
                "subject": subject,
                "requestId": context.request_id,
                "traceId": context.trace_id,
                "identityKind": context.credential.principal.kind,
                "parameterSummary": "rejected",
            },
        )


_SENSITIVE_KEYS = frozenset(
    {
        "token",
        "accesstoken",
        "refreshtoken",
        "confirmationtoken",
        "cookie",
        "password",
        "prompt",
        "chainofthought",
    }
)


def _safe_result(value: Any, *, depth: int = 0) -> Any:
    if depth > 6:
        return None
    if value is None or isinstance(value, bool | int | float):
        return value
    if isinstance(value, str):
        return value[:1000]
    if isinstance(value, list):
        return [_safe_result(item, depth=depth + 1) for item in value[:50]]
    if isinstance(value, dict):
        return {
            str(key)[:100]: _safe_result(item, depth=depth + 1)
            for key, item in list(value.items())[:100]
            if str(key).replace("_", "").replace("-", "").lower() not in _SENSITIVE_KEYS
        }
    raise TypeError("unsupported tool response")


def _result_count(value: Any) -> int | None:
    if isinstance(value, list):
        return len(value)
    if isinstance(value, dict):
        for key in ("items", "content", "products", "orders", "reservations", "anomalies"):
            items = value.get(key)
            if isinstance(items, list):
                return len(items)
    return None


def _span_id() -> str:
    value = secrets.token_hex(8)
    return value if value != "0" * 16 else "0" * 15 + "1"


USER_TOOL_SPECS = (
    ToolSpec(
        "search_products",
        IdentityKind.USER,
        "catalog:read",
        "GET",
        "/agent/api/v1/tools/products",
        SearchProductsInput,
        "product",
    ),
    ToolSpec(
        "get_product",
        IdentityKind.USER,
        "catalog:read",
        "GET",
        "/agent/api/v1/tools/products/{productId}",
        ProductIdInput,
        "product",
    ),
    ToolSpec(
        "compare_products",
        IdentityKind.USER,
        "catalog:read",
        "POST",
        "/agent/api/v1/tools/product-comparisons",
        CompareProductsInput,
        "product-comparison",
    ),
    ToolSpec(
        "list_my_orders",
        IdentityKind.USER,
        "orders:self:read",
        "GET",
        "/agent/api/v1/tools/orders",
        PageInput,
        "order",
    ),
    ToolSpec(
        "list_my_reservations",
        IdentityKind.USER,
        "reservations:self:read",
        "GET",
        "/agent/api/v1/tools/reservations",
        ReservationPageInput,
        "reservation",
    ),
    ToolSpec(
        "create_purchase_draft",
        IdentityKind.USER,
        "purchase-drafts:create",
        "POST",
        "/agent/api/v1/tools/purchase-drafts",
        PurchaseDraftInput,
        "purchase-draft",
    ),
)

ADMIN_TOOL_SPECS = (
    ToolSpec(
        "read_statistics",
        IdentityKind.ADMINISTRATOR,
        None,
        "GET",
        "/admin/api/v1/agent-tools/statistics",
        StatisticsInput,
        "statistics",
    ),
    ToolSpec(
        "read_anomaly_summary",
        IdentityKind.ADMINISTRATOR,
        None,
        "GET",
        "/admin/api/v1/agent-tools/anomalies",
        AnomalySummaryInput,
        "anomaly-summary",
    ),
    ToolSpec(
        "create_configuration_draft",
        IdentityKind.ADMINISTRATOR,
        None,
        "POST",
        "/admin/api/v1/agent-tools/configuration-drafts",
        ConfigurationDraftInput,
        "configuration-draft",
    ),
)

USER_TOOLS = ToolRegistry(IdentityKind.USER, USER_TOOL_SPECS)
ADMIN_TOOLS = ToolRegistry(IdentityKind.ADMINISTRATOR, ADMIN_TOOL_SPECS)
