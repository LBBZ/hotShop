from __future__ import annotations

import asyncio
from datetime import timedelta
from typing import Protocol, TypeVar

from redis.asyncio import Redis

from hotshop_agent.domain import AgentMessage, AgentRun, AgentSession, utc_now

T = TypeVar("T", AgentSession, AgentMessage, AgentRun)


class StateStore(Protocol):
    async def ready(self) -> bool: ...

    async def close(self) -> None: ...

    async def put_session(self, session: AgentSession) -> None: ...

    async def get_session(self, session_id: str) -> AgentSession | None: ...

    async def put_message(self, message: AgentMessage) -> None: ...

    async def get_message(self, message_id: str) -> AgentMessage | None: ...

    async def put_run(self, run: AgentRun) -> None: ...

    async def get_run(self, run_id: str) -> AgentRun | None: ...


class InMemoryStateStore:
    def __init__(self) -> None:
        self.sessions: dict[str, AgentSession] = {}
        self.messages: dict[str, AgentMessage] = {}
        self.runs: dict[str, AgentRun] = {}
        self._lock = asyncio.Lock()

    async def ready(self) -> bool:
        return True

    async def close(self) -> None:
        return None

    async def put_session(self, session: AgentSession) -> None:
        async with self._lock:
            self.sessions[session.id] = session

    async def get_session(self, session_id: str) -> AgentSession | None:
        async with self._lock:
            session = self.sessions.get(session_id)
            if session is not None and session.expires_at <= utc_now():
                return None
            return session

    async def put_message(self, message: AgentMessage) -> None:
        async with self._lock:
            self.messages[message.id] = message

    async def get_message(self, message_id: str) -> AgentMessage | None:
        async with self._lock:
            return self.messages.get(message_id)

    async def put_run(self, run: AgentRun) -> None:
        async with self._lock:
            self.runs[run.id] = run

    async def get_run(self, run_id: str) -> AgentRun | None:
        async with self._lock:
            return self.runs.get(run_id)


class RedisStateStore:
    def __init__(
        self,
        redis: Redis,
        *,
        prefix: str,
        session_ttl_seconds: int,
        run_ttl_seconds: int,
    ) -> None:
        self._redis = redis
        self._prefix = prefix
        self._session_ttl = session_ttl_seconds
        self._run_ttl = run_ttl_seconds

    async def ready(self) -> bool:
        try:
            return bool(await self._redis.ping())
        except Exception:
            return False

    async def close(self) -> None:
        await self._redis.aclose()

    async def put_session(self, session: AgentSession) -> None:
        await self._put("session", session.id, session, self._session_ttl)

    async def get_session(self, session_id: str) -> AgentSession | None:
        return await self._get("session", session_id, AgentSession)

    async def put_message(self, message: AgentMessage) -> None:
        await self._put("message", message.id, message, self._session_ttl)

    async def get_message(self, message_id: str) -> AgentMessage | None:
        return await self._get("message", message_id, AgentMessage)

    async def put_run(self, run: AgentRun) -> None:
        await self._put("run", run.id, run, self._run_ttl)

    async def get_run(self, run_id: str) -> AgentRun | None:
        return await self._get("run", run_id, AgentRun)

    async def _put(self, kind: str, item_id: str, item: T, ttl: int) -> None:
        await self._redis.set(
            f"{self._prefix}{kind}:{item_id}",
            item.model_dump_json(),
            ex=timedelta(seconds=ttl),
        )

    async def _get(self, kind: str, item_id: str, model: type[T]) -> T | None:
        value = await self._redis.get(f"{self._prefix}{kind}:{item_id}")
        if value is None:
            return None
        return model.model_validate_json(value)
