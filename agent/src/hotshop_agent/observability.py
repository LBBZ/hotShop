from __future__ import annotations

import asyncio
import json
import logging
import re
import secrets
import time
from collections.abc import AsyncIterator, Coroutine
from contextlib import asynccontextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx

from hotshop_agent.config import Settings

REQUEST_ID = ContextVar("request_id", default="")
TRACE_ID = ContextVar("trace_id", default="")
SPAN_ID = ContextVar("span_id", default="")
TRACE_FLAGS = ContextVar("trace_flags", default="01")
TRACEPARENT = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
SECRET = re.compile(
    r"(?i)\b(authorization|cookie|password|api[-_ ]?key|access[-_ ]?token|"
    r"refresh[-_ ]?token|delegation[-_ ]?token|prompt)\s*[:=]\s*[^\s,;]+"
)
BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")


def sanitize(value: str) -> str:
    safe = BEARER.sub("Bearer [REDACTED]", value)
    safe = SECRET.sub("[REDACTED]", safe)
    return safe[:4096]


class JsonFormatter(logging.Formatter):
    def __init__(self, service: str, environment: str) -> None:
        super().__init__()
        self._service = service
        self._environment = environment

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "service": self._service,
            "environment": self._environment,
            "event": sanitize(str(getattr(record, "event", "application.log"))),
            "name": sanitize(record.name),
            "requestId": REQUEST_ID.get(),
            "traceId": TRACE_ID.get(),
            "spanId": SPAN_ID.get(),
            "outcome": sanitize(str(getattr(record, "outcome", "unknown"))),
            "errorType": sanitize(str(getattr(record, "errorType", ""))),
            "message": sanitize(record.getMessage()),
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_logging(settings: Settings) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter("agent", settings.environment))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)


@dataclass(frozen=True)
class TraceState:
    trace_id: str
    span_id: str
    parent_span_id: str | None
    flags: str


class Telemetry:
    def __init__(
        self, settings: Settings, client: httpx.AsyncClient | None = None
    ) -> None:
        self._settings = settings
        self._client = client or httpx.AsyncClient()
        self._owns_client = client is None
        self._pending: set[asyncio.Task[None]] = set()

    async def close(self) -> None:
        if self._pending:
            await asyncio.gather(*tuple(self._pending), return_exceptions=True)
        if self._owns_client:
            await self._client.aclose()

    @asynccontextmanager
    async def span(
        self,
        name: str,
        *,
        kind: str = "INTERNAL",
        tags: dict[str, str] | None = None,
        remote_parent: TraceState | None = None,
    ) -> AsyncIterator[TraceState]:
        sampled = secrets.randbelow(1_000_000) < int(
            self._settings.trace_sample_ratio * 1_000_000
        )
        current_trace = TRACE_ID.get()
        current_span = SPAN_ID.get()
        trace_id = (
            remote_parent.trace_id
            if remote_parent
            else current_trace or secrets.token_hex(16)
        )
        parent_id = (
            remote_parent.span_id if remote_parent else current_span or None
        )
        span_id = secrets.token_hex(8)
        flags = remote_parent.flags if remote_parent else ("01" if sampled else "00")
        trace_token = TRACE_ID.set(trace_id)
        span_token = SPAN_ID.set(span_id)
        flags_token = TRACE_FLAGS.set(flags)
        started_ns = time.time_ns()
        error_type = ""
        try:
            yield TraceState(trace_id, span_id, parent_id, flags)
        except BaseException as exc:
            error_type = type(exc).__name__
            raise
        finally:
            duration_ns = max(1, time.time_ns() - started_ns)
            if flags.endswith("1"):
                self._schedule_export(
                    self._export(
                        name,
                        kind,
                        TraceState(trace_id, span_id, parent_id, flags),
                        started_ns,
                        duration_ns,
                        tags or {},
                        error_type,
                    )
                )
            TRACE_ID.reset(trace_token)
            SPAN_ID.reset(span_token)
            TRACE_FLAGS.reset(flags_token)

    def _schedule_export(self, export: Coroutine[Any, Any, None]) -> None:
        task: asyncio.Task[None] = asyncio.create_task(export)
        self._pending.add(task)
        task.add_done_callback(self._pending.discard)

    async def _export(
        self,
        name: str,
        kind: str,
        state: TraceState,
        started_ns: int,
        duration_ns: int,
        tags: dict[str, str],
        error_type: str,
    ) -> None:
        safe_tags = {
            "service.name": "agent",
            "deployment.environment": self._settings.environment,
            **{key: sanitize(value) for key, value in tags.items()},
        }
        if error_type:
            safe_tags["error.type"] = error_type
        span: dict[str, Any] = {
            "traceId": state.trace_id,
            "id": state.span_id,
            "name": name,
            "kind": kind,
            "timestamp": started_ns // 1_000,
            "duration": duration_ns // 1_000,
            "localEndpoint": {"serviceName": "agent"},
            "tags": safe_tags,
        }
        if state.parent_span_id:
            span["parentId"] = state.parent_span_id
        try:
            await self._client.post(
                self._settings.zipkin_endpoint,
                json=[span],
                headers={"Content-Type": "application/json"},
                timeout=2.0,
            )
        except Exception:
            logging.getLogger(__name__).warning(
                "trace export unavailable",
                extra={"event": "trace.export", "outcome": "failure", "errorType": "HTTPError"},
            )


def parse_remote_parent(value: str | None) -> TraceState | None:
    if not value:
        return None
    match = TRACEPARENT.fullmatch(value)
    if (
        match is None
        or match.group(1) == "ff"
        or match.group(2) == "0" * 32
        or match.group(3) == "0" * 16
    ):
        return None
    return TraceState(match.group(2), match.group(3), None, match.group(4))


def request_id(value: str | None) -> str:
    return value if value and REQUEST_ID_PATTERN.fullmatch(value) else str(secrets.token_hex(16))


def set_request_id(value: str) -> Token[str]:
    return REQUEST_ID.set(value)


def reset_request_id(token: Token[str]) -> None:
    REQUEST_ID.reset(token)
