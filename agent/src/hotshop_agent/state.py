from __future__ import annotations

import asyncio
from collections.abc import Awaitable
from datetime import timedelta
from typing import Protocol, TypeVar, cast

from redis.asyncio import Redis

from hotshop_agent.domain import AgentMessage, AgentRun, AgentSession, ConversationTurn, utc_now

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

    async def claim_run(self, run: AgentRun) -> str: ...

    async def release_run(self, run: AgentRun) -> None: ...

    async def get_history(self, session_id: str) -> list[ConversationTurn]: ...

    async def append_turn(self, session_id: str, turn: ConversationTurn) -> None: ...


class InMemoryStateStore:
    def __init__(self) -> None:
        self.sessions: dict[str, AgentSession] = {}
        self.messages: dict[str, AgentMessage] = {}
        self.runs: dict[str, AgentRun] = {}
        self._lock = asyncio.Lock()
        self._message_runs: dict[str, str] = {}
        self._session_runs: dict[str, str] = {}
        self._history: dict[str, list[ConversationTurn]] = {}

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

    async def claim_run(self, run: AgentRun) -> str:
        async with self._lock:
            existing = self._message_runs.get(run.message_id)
            if existing is not None:
                return existing
            if run.session_id in self._session_runs:
                return ""
            self._message_runs[run.message_id] = run.id
            self._session_runs[run.session_id] = run.id
            self.runs[run.id] = run
            return run.id

    async def release_run(self, run: AgentRun) -> None:
        async with self._lock:
            if self._session_runs.get(run.session_id) == run.id:
                del self._session_runs[run.session_id]

    async def get_history(self, session_id: str) -> list[ConversationTurn]:
        async with self._lock:
            return list(self._history.get(session_id, []))

    async def append_turn(self, session_id: str, turn: ConversationTurn) -> None:
        async with self._lock:
            history = self._history.setdefault(session_id, [])
            history.append(turn)
            del history[:-HISTORY_MAX_TURNS]


# Bound both storage and the amount of prior conversation admitted to a prompt.
HISTORY_MAX_TURNS = 12


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

    async def claim_run(self, run: AgentRun) -> str:
        # One message is executed at most once throughout the session TTL, and
        # concurrent turns cannot read or append history out of order.
        result = await cast(
            Awaitable[str],
            self._redis.eval(
                """
            local existing = redis.call('GET', KEYS[1])
            if existing then return existing end
            if redis.call('EXISTS', KEYS[2]) == 1 then return '' end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[3])
            redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[4])
            return ARGV[1]
            """,
                3,
                f"{self._prefix}message-run:{run.message_id}",
                f"{self._prefix}session-run:{run.session_id}",
                f"{self._prefix}run:{run.id}",
                run.id,
                run.model_dump_json(),
                str(self._session_ttl),
                str(self._run_ttl),
            ),
        )
        return result

    async def release_run(self, run: AgentRun) -> None:
        await cast(
            Awaitable[str],
            self._redis.eval(
                """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """,
                1,
                f"{self._prefix}session-run:{run.session_id}",
                run.id,
            ),
        )

    async def get_history(self, session_id: str) -> list[ConversationTurn]:
        values = await cast(
            Awaitable[list[str]], self._redis.lrange(f"{self._prefix}history:{session_id}", 0, -1)
        )
        return [ConversationTurn.model_validate_json(value) for value in values]

    async def append_turn(self, session_id: str, turn: ConversationTurn) -> None:
        key = f"{self._prefix}history:{session_id}"
        async with self._redis.pipeline(transaction=True) as pipeline:
            pipeline.rpush(key, turn.model_dump_json())
            pipeline.ltrim(key, -HISTORY_MAX_TURNS, -1)
            pipeline.expire(key, self._session_ttl)
            await pipeline.execute()

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
