from __future__ import annotations

import httpx
from redis.asyncio import Redis

from hotshop_agent.config import Settings
from hotshop_agent.domain import IdentityKind
from hotshop_agent.exchange import TokenExchangeClient
from hotshop_agent.graph import build_graph
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.observability import Telemetry
from hotshop_agent.providers.base import ModelProvider
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.providers.qwen import QwenModel
from hotshop_agent.registry import ADMIN_TOOL_SPECS, USER_TOOL_SPECS, ToolRegistry
from hotshop_agent.reliability import CircuitBreaker, ConcurrencyLimiter, ReliableModel
from hotshop_agent.security import ClientAssertionSigner, JwtVerifier
from hotshop_agent.service import AgentService
from hotshop_agent.state import InMemoryStateStore, RedisStateStore, StateStore


class Container:
    def __init__(
        self,
        *,
        settings: Settings,
        http_client: httpx.AsyncClient,
        store: StateStore,
        verifier: JwtVerifier,
        exchange: TokenExchangeClient,
        service: AgentService,
        telemetry: Telemetry,
    ) -> None:
        self.settings = settings
        self.http_client = http_client
        self.store = store
        self.verifier = verifier
        self.exchange = exchange
        self.service = service
        self.telemetry = telemetry

    async def close(self) -> None:
        await self.service.shutdown()
        await self.store.close()
        await self.telemetry.close()
        await self.http_client.aclose()


def build_container(
    settings: Settings,
    *,
    http_client: httpx.AsyncClient | None = None,
    provider: ModelProvider | None = None,
    store: StateStore | None = None,
) -> Container:
    client = http_client or httpx.AsyncClient()
    state_store = store or _build_store(settings)
    verifier = JwtVerifier(settings)
    signer = ClientAssertionSigner(settings)
    exchange = TokenExchangeClient(settings, client, signer, verifier)
    selected_provider = provider or _build_provider(settings, client)
    reliable = ReliableModel(
        selected_provider,
        timeout_seconds=settings.model_timeout_seconds,
        max_retries=settings.model_max_retries,
        retry_base_seconds=settings.model_retry_base_seconds,
        breaker=CircuitBreaker(
            settings.circuit_failure_threshold,
            settings.circuit_recovery_seconds,
        ),
        limiter=ConcurrencyLimiter(
            settings.global_concurrency_limit,
            settings.user_concurrency_limit,
        ),
    )
    metrics = AgentMetrics()
    telemetry = Telemetry(settings)
    user_tools = ToolRegistry(
        IdentityKind.USER,
        USER_TOOL_SPECS,
        client=client,
        verifier=verifier,
        base_url=settings.portal_base_url,
        timeout_seconds=settings.tool_timeout_seconds,
    )
    administrator_tools = ToolRegistry(
        IdentityKind.ADMINISTRATOR,
        ADMIN_TOOL_SPECS,
        client=client,
        verifier=verifier,
        base_url=settings.administrator_base_url,
        timeout_seconds=settings.tool_timeout_seconds,
    )
    service = AgentService(
        settings,
        state_store,
        reliable,
        metrics,
        build_graph(user_tools, administrator_tools),
        telemetry,
    )
    return Container(
        settings=settings,
        http_client=client,
        store=state_store,
        verifier=verifier,
        exchange=exchange,
        service=service,
        telemetry=telemetry,
    )


def _build_store(settings: Settings) -> StateStore:
    if settings.state_backend == "redis":
        redis = Redis.from_url(
            settings.redis_url.get_secret_value(),
            decode_responses=True,
        )
        return RedisStateStore(
            redis,
            prefix=settings.redis_key_prefix,
            session_ttl_seconds=settings.session_ttl_seconds,
            run_ttl_seconds=settings.run_ttl_seconds,
        )
    return InMemoryStateStore()


def _build_provider(settings: Settings, client: httpx.AsyncClient) -> ModelProvider:
    if settings.provider == "qwen":
        assert settings.qwen_api_key is not None
        return QwenModel(
            client,
            base_url=settings.qwen_base_url,
            api_key=settings.qwen_api_key.get_secret_value(),
            model=settings.qwen_model,
            input_cost_per_million_usd=settings.qwen_input_cost_per_million_usd,
            output_cost_per_million_usd=settings.qwen_output_cost_per_million_usd,
        )
    return FakeModel()
