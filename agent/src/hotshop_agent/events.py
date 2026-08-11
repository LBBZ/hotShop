from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from hotshop_agent.security import safe_json

EventType = Literal[
    "session.created",
    "message.started",
    "message.delta",
    "message.completed",
    "tool.started",
    "tool.completed",
    "tool.failed",
    "rag.completed",
    "usage",
    "error",
    "done",
]

EVENT_DATA_KEYS: dict[str, frozenset[str]] = {
    "session.created": frozenset({"state", "scopes"}),
    "message.started": frozenset({"state"}),
    "message.delta": frozenset({"delta"}),
    "message.completed": frozenset({"state"}),
    "tool.started": frozenset({"tool", "resourceType"}),
    "tool.completed": frozenset({"tool", "resourceType", "outcome", "summary"}),
    "tool.failed": frozenset({"tool", "resourceType", "outcome", "summary", "code"}),
    "rag.completed": frozenset({"outcome", "citations"}),
    "usage": frozenset({"inputTokens", "outputTokens", "estimatedCostUsd"}),
    "error": frozenset({"code", "message", "retryable"}),
    "done": frozenset({"state"}),
}

MAX_SENSITIVE_CANDIDATE_CHARS = 4096
REDACTION = "[REDACTED]"

_ASSIGNMENT_KEY = r"(?:api(?:_|-| )?key|token|cookie|client(?:_|-| )?assertion)"
_PEM_LABEL = r"(?:PKCS#8 |RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY"
_BEARER_START = re.compile(r"(?i)(?<![A-Za-z0-9_])bearer[ \t\r\n]+")
_ASSIGNMENT_START = re.compile(rf"(?i)(?<![A-Za-z0-9_]){_ASSIGNMENT_KEY}[ \t\r\n]*[:=][ \t\r\n]*")
_ASSIGNMENT_WAITING_FOR_SEPARATOR = re.compile(
    rf"(?i)(?<![A-Za-z0-9_]){_ASSIGNMENT_KEY}[ \t\r\n]*$"
)
_PEM_START = re.compile(rf"-----BEGIN (?P<label>{_PEM_LABEL})-----")
_TAG_START = re.compile(r"(?i)<(?P<label>thinking|analysis|system)>")
_JWT_START = re.compile(r"(?<![A-Za-z0-9_-])eyJ")
_JWT_COMPLETE = re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")
_JWT_CHARS = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-.")

_LITERAL_STARTS = (
    "bearer",
    "api_key",
    "api-key",
    "api key",
    "apikey",
    "token",
    "cookie",
    "client_assertion",
    "client-assertion",
    "client assertion",
    "clientassertion",
    "eyJ",
    "-----BEGIN PRIVATE KEY-----",
    "-----BEGIN PKCS#8 PRIVATE KEY-----",
    "-----BEGIN RSA PRIVATE KEY-----",
    "-----BEGIN EC PRIVATE KEY-----",
    "-----BEGIN OPENSSH PRIVATE KEY-----",
    "-----BEGIN ENCRYPTED PRIVATE KEY-----",
    "<thinking>",
    "<analysis>",
    "<system>",
)


class _CandidateKind(StrEnum):
    BEARER = "bearer"
    ASSIGNMENT = "assignment"
    JWT = "jwt"
    PEM = "pem"
    TAG = "tag"


@dataclass(frozen=True)
class _Candidate:
    kind: _CandidateKind
    start: int
    value_start: int
    marker: str | None = None


class StreamingSanitizer:
    """Incrementally redact sensitive values without unbounded buffering."""

    def __init__(self, max_candidate_chars: int = MAX_SENSITIVE_CANDIDATE_CHARS) -> None:
        if max_candidate_chars < 64:
            raise ValueError("max_candidate_chars must be at least 64")
        self.max_candidate_chars = max_candidate_chars
        self._buffer = ""
        self._discard_until_whitespace = False
        self._discard_jwt = False
        self._discard_value_stage: str | None = None
        self._discard_marker: str | None = None
        self._discard_tail = ""
        self._closed = False

    @property
    def pending_chars(self) -> int:
        return len(self._buffer) + len(self._discard_tail)

    def feed(self, text: str) -> tuple[str, ...]:
        if self._closed:
            raise RuntimeError("StreamingSanitizer is closed")
        if not text:
            return ()
        outputs: list[str] = []
        remainder = self._consume_discard(text)
        if remainder:
            self._buffer += remainder
            self._process(outputs, final=False)
        return tuple(part for part in outputs if part)

    def flush(self) -> tuple[str, ...]:
        if self._closed:
            return ()
        outputs: list[str] = []
        if (
            self._discard_until_whitespace
            or self._discard_jwt
            or self._discard_value_stage
            or self._discard_marker
        ):
            self._clear_discard()
        self._process(outputs, final=True)
        self._closed = True
        return tuple(part for part in outputs if part)

    def discard(self) -> None:
        """Drop all unresolved content on cancellation or failure."""
        self._buffer = ""
        self._clear_discard()
        self._closed = True

    def _process(self, outputs: list[str], *, final: bool) -> None:
        while self._buffer:
            candidate = self._find_candidate(self._buffer)
            if candidate is None:
                if final:
                    outputs.append(self._buffer)
                    self._buffer = ""
                    return
                carry = self._partial_start_length(self._buffer)
                if carry:
                    if carry > self.max_candidate_chars:
                        outputs.append(REDACTION)
                        self._buffer = ""
                        self._discard_value_stage = "separator"
                        return
                    safe_length = len(self._buffer) - carry
                    if safe_length:
                        outputs.append(self._buffer[:safe_length])
                        self._buffer = self._buffer[safe_length:]
                else:
                    outputs.append(self._buffer)
                    self._buffer = ""
                return

            if candidate.start:
                outputs.append(self._buffer[: candidate.start])
                self._buffer = self._buffer[candidate.start :]
                candidate = _Candidate(
                    candidate.kind,
                    0,
                    candidate.value_start - candidate.start,
                    candidate.marker,
                )

            if candidate.kind in {_CandidateKind.BEARER, _CandidateKind.ASSIGNMENT}:
                if self._process_whitespace_value(candidate, outputs, final=final):
                    continue
                return
            if candidate.kind is _CandidateKind.JWT:
                if self._process_jwt(outputs, final=final):
                    continue
                return
            if candidate.kind in {_CandidateKind.PEM, _CandidateKind.TAG}:
                if self._process_marker(candidate, outputs, final=final):
                    continue
                return

    def _process_whitespace_value(
        self,
        candidate: _Candidate,
        outputs: list[str],
        *,
        final: bool,
    ) -> bool:
        value = self._buffer[candidate.value_start :]
        terminator = next((index for index, char in enumerate(value) if char.isspace()), None)
        if terminator is not None:
            if terminator == 0:
                if candidate.kind is _CandidateKind.BEARER:
                    return False
                self._buffer = self._buffer[candidate.value_start + 1 :]
                outputs.append(REDACTION)
                return True
            self._buffer = self._buffer[candidate.value_start + terminator :]
            outputs.append(REDACTION)
            return True
        if final:
            outputs.append(REDACTION if value else self._buffer)
            self._buffer = ""
            return False
        if len(self._buffer) > self.max_candidate_chars:
            outputs.append(REDACTION)
            self._buffer = ""
            if value:
                self._discard_until_whitespace = True
            else:
                self._discard_value_stage = "value"
        return False

    def _process_jwt(self, outputs: list[str], *, final: bool) -> bool:
        end = next(
            (index for index, char in enumerate(self._buffer) if char not in _JWT_CHARS),
            None,
        )
        if end is not None:
            candidate = self._buffer[:end]
            self._buffer = self._buffer[end:]
            outputs.append(REDACTION if _JWT_COMPLETE.fullmatch(candidate) else candidate)
            return True
        if final:
            outputs.append(REDACTION if _JWT_COMPLETE.fullmatch(self._buffer) else self._buffer)
            self._buffer = ""
            return False
        if len(self._buffer) > self.max_candidate_chars:
            outputs.append(REDACTION)
            self._buffer = ""
            self._discard_jwt = True
        return False

    def _process_marker(
        self,
        candidate: _Candidate,
        outputs: list[str],
        *,
        final: bool,
    ) -> bool:
        assert candidate.marker is not None
        search_space = self._buffer.casefold()
        marker = candidate.marker.casefold()
        marker_at = search_space.find(marker, candidate.value_start)
        if marker_at >= 0:
            self._buffer = self._buffer[marker_at + len(candidate.marker) :]
            outputs.append(REDACTION)
            return True
        if final:
            outputs.append(REDACTION)
            self._buffer = ""
            return False
        if len(self._buffer) > self.max_candidate_chars:
            outputs.append(REDACTION)
            self._discard_marker = candidate.marker
            self._discard_tail = self._buffer[-(len(candidate.marker) - 1) :]
            self._buffer = ""
        return False

    def _consume_discard(self, text: str) -> str:
        if self._discard_value_stage is not None:
            return self._consume_value_discard(text)
        if self._discard_until_whitespace:
            boundary = next((index for index, char in enumerate(text) if char.isspace()), None)
            if boundary is None:
                return ""
            self._discard_until_whitespace = False
            return text[boundary:]
        if self._discard_jwt:
            boundary = next(
                (index for index, char in enumerate(text) if char not in _JWT_CHARS),
                None,
            )
            if boundary is None:
                return ""
            self._discard_jwt = False
            return text[boundary:]
        if self._discard_marker is not None:
            combined = self._discard_tail + text
            marker_at = combined.casefold().find(self._discard_marker.casefold())
            if marker_at < 0:
                keep = len(self._discard_marker) - 1
                self._discard_tail = combined[-keep:] if keep else ""
                return ""
            remainder_at = marker_at + len(self._discard_marker)
            self._clear_discard()
            return combined[remainder_at:]
        return text

    def _consume_value_discard(self, text: str) -> str:
        stage = self._discard_value_stage
        for index, char in enumerate(text):
            if stage == "separator":
                if char.isspace():
                    continue
                if char in {":", "="}:
                    stage = "value"
                    continue
                self._discard_value_stage = None
                return text[index:]
            if stage == "value":
                if char.isspace():
                    continue
                stage = "content"
            if stage == "content" and char.isspace():
                self._discard_value_stage = None
                return text[index:]
        self._discard_value_stage = stage
        return ""

    def _clear_discard(self) -> None:
        self._discard_until_whitespace = False
        self._discard_jwt = False
        self._discard_value_stage = None
        self._discard_marker = None
        self._discard_tail = ""

    @staticmethod
    def _find_candidate(text: str) -> _Candidate | None:
        matches: list[_Candidate] = []
        if match := _BEARER_START.search(text):
            matches.append(_Candidate(_CandidateKind.BEARER, match.start(), match.end()))
        if match := _ASSIGNMENT_START.search(text):
            matches.append(_Candidate(_CandidateKind.ASSIGNMENT, match.start(), match.end()))
        if match := _JWT_START.search(text):
            matches.append(_Candidate(_CandidateKind.JWT, match.start(), match.start()))
        if match := _PEM_START.search(text):
            label = match.group("label")
            matches.append(
                _Candidate(
                    _CandidateKind.PEM,
                    match.start(),
                    match.end(),
                    f"-----END {label}-----",
                )
            )
        if match := _TAG_START.search(text):
            label = match.group("label")
            matches.append(
                _Candidate(
                    _CandidateKind.TAG,
                    match.start(),
                    match.end(),
                    f"</{label}>",
                )
            )
        return min(matches, key=lambda item: item.start) if matches else None

    @staticmethod
    def _partial_start_length(text: str) -> int:
        waiting = _ASSIGNMENT_WAITING_FOR_SEPARATOR.search(text)
        longest = len(text) - waiting.start() if waiting else 0
        folded = text.casefold()
        for literal in _LITERAL_STARTS:
            candidate = literal.casefold()
            maximum = min(len(folded), len(candidate))
            for size in range(maximum, 0, -1):
                if folded.endswith(candidate[:size]):
                    longest = max(longest, size)
                    break
        return longest


def sanitize_text(text: str) -> str:
    sanitizer = StreamingSanitizer()
    return "".join((*sanitizer.feed(text), *sanitizer.flush()))


class StreamEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: EventType
    session_id: str = Field(alias="sessionId")
    run_id: str | None = Field(default=None, alias="runId")
    message_id: str | None = Field(default=None, alias="messageId")
    sequence: int = Field(ge=1)
    data: dict[str, Any]

    @field_validator("data")
    @classmethod
    def validate_data(cls, value: dict[str, Any], info: Any) -> dict[str, Any]:
        event_type = info.data.get("type")
        if event_type not in EVENT_DATA_KEYS or not EVENT_DATA_KEYS[event_type].issuperset(value):
            raise ValueError("SSE event contains non-allowlisted data")
        return value

    def encode(self) -> str:
        payload = self.model_dump(by_alias=True, exclude_none=True)
        return f"event: {self.type}\ndata: {safe_json(payload)}\n\n"
