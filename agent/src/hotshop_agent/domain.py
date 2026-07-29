from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


def utc_now() -> datetime:
    return datetime.now(UTC)


class IdentityKind(StrEnum):
    USER = "user"
    ADMINISTRATOR = "administrator"
    DELEGATION = "delegation"


class Principal(BaseModel):
    model_config = ConfigDict(frozen=True)

    kind: IdentityKind
    subject_user_id: str
    username: str
    jti: str
    expires_at: datetime
    authorized_party: str | None = None
    scopes: frozenset[str] = frozenset()


class Credential(BaseModel):
    model_config = ConfigDict(frozen=True)

    token: str = Field(repr=False)
    principal: Principal


class SessionState(StrEnum):
    ACTIVE = "ACTIVE"
    CLOSED = "CLOSED"
    EXPIRED = "EXPIRED"


class MessageState(StrEnum):
    CREATED = "CREATED"
    COMPLETED = "COMPLETED"


class RunState(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"


class AgentSession(BaseModel):
    id: str
    owner_id: str
    identity_kind: IdentityKind
    state: SessionState
    scopes: frozenset[str]
    created_at: datetime
    expires_at: datetime


class AgentMessage(BaseModel):
    id: str
    session_id: str
    content: str = Field(repr=False)
    state: MessageState
    created_at: datetime


class AgentRun(BaseModel):
    id: str
    session_id: str
    message_id: str
    owner_id: str
    state: RunState
    created_at: datetime
    updated_at: datetime
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost_usd: float = 0.0
