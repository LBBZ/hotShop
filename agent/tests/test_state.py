from __future__ import annotations

import asyncio
import os
import uuid
from datetime import timedelta

import pytest
from redis.asyncio import Redis

from hotshop_agent.domain import (
    AgentRun,
    AgentSession,
    ConversationTurn,
    IdentityKind,
    RunState,
    SessionState,
    utc_now,
)
from hotshop_agent.state import RedisStateStore


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}
        self.expirations: dict[str, timedelta] = {}
        self.closed = False

    async def ping(self) -> bool:
        return True

    async def set(self, key: str, value: str, *, ex: timedelta) -> None:
        self.values[key] = value
        self.expirations[key] = ex

    async def get(self, key: str) -> str | None:
        return self.values.get(key)

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.asyncio
async def test_redis_store_uses_fixed_prefix_and_explicit_ttl() -> None:
    redis = FakeRedis()
    store = RedisStateStore(
        redis,  # type: ignore[arg-type]
        prefix="hotshop:agent:",
        session_ttl_seconds=3600,
        run_ttl_seconds=900,
    )
    now = utc_now()
    session = AgentSession(
        id="session-id",
        owner_id="42",
        identity_kind=IdentityKind.USER,
        state=SessionState.ACTIVE,
        scopes=frozenset({"catalog:read"}),
        created_at=now,
        expires_at=now + timedelta(hours=1),
    )
    await store.put_session(session)
    restored = await store.get_session(session.id)
    assert restored == session
    assert set(redis.values) == {"hotshop:agent:session:session-id"}
    assert redis.expirations["hotshop:agent:session:session-id"] == timedelta(seconds=3600)
    assert await store.ready()
    await store.close()
    assert redis.closed


@pytest.mark.redis
@pytest.mark.asyncio
async def test_real_redis_atomic_run_claim_and_history_survive_store_recreation() -> None:
    url = os.environ.get("AGENT_TEST_REDIS_URL")
    if not url:
        pytest.skip("AGENT_TEST_REDIS_URL must identify an isolated test Redis")
    first_client = Redis.from_url(url, decode_responses=True)
    second_client = Redis.from_url(url, decode_responses=True)
    first = RedisStateStore(
        first_client, prefix="hotshop:agent:", session_ttl_seconds=120, run_ttl_seconds=60
    )
    second = RedisStateStore(
        second_client, prefix="hotshop:agent:", session_ttl_seconds=120, run_ttl_seconds=60
    )
    session_id, message_id = str(uuid.uuid4()), str(uuid.uuid4())
    now = utc_now()
    run = AgentRun(
        id=str(uuid.uuid4()),
        session_id=session_id,
        message_id=message_id,
        owner_id="42",
        state=RunState.QUEUED,
        created_at=now,
        updated_at=now,
    )
    candidate = run.model_copy(update={"id": str(uuid.uuid4())})
    next_run = run.model_copy(update={"id": str(uuid.uuid4()), "message_id": str(uuid.uuid4())})
    try:
        left, right = await asyncio.gather(first.claim_run(run), second.claim_run(candidate))
        assert left == right
        winner = await first.get_run(left)
        assert winner is not None
        assert await second.claim_run(next_run) == ""
        await first.append_turn(
            session_id,
            ConversationTurn(
                message_id=message_id,
                user="blue tea",
                assistant="The blue tea is selected",
            ),
        )
        assert (await second.get_history(session_id))[0].user == "blue tea"
        assert 0 < await first_client.ttl(f"hotshop:agent:history:{session_id}") <= 120
        # A stale worker cannot release another run's lease.
        await second.release_run(next_run)
        assert await second.claim_run(next_run) == ""
        await first.release_run(winner)
        assert await second.claim_run(next_run) == next_run.id
        # The deduplication key outlives run state: an expired run is not executed twice.
        await first_client.delete(f"hotshop:agent:run:{left}")
        assert await second.claim_run(candidate) == left
    finally:
        await first_client.delete(
            f"hotshop:agent:run:{run.id}",
            f"hotshop:agent:run:{candidate.id}",
            f"hotshop:agent:run:{next_run.id}",
            f"hotshop:agent:message-run:{message_id}",
            f"hotshop:agent:message-run:{next_run.message_id}",
            f"hotshop:agent:session-run:{session_id}",
            f"hotshop:agent:history:{session_id}",
        )
        await first.close()
        await second.close()
