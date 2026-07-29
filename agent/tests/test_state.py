from __future__ import annotations

from datetime import timedelta

import pytest

from hotshop_agent.domain import AgentSession, IdentityKind, SessionState, utc_now
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
