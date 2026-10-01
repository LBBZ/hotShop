from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import httpx
import pytest
from prometheus_client import generate_latest

from hotshop_agent.config import Settings
from hotshop_agent.container import build_container
from hotshop_agent.domain import AgentRun, ConversationTurn, Credential, IdentityKind, RunState
from hotshop_agent.embeddings import BailianEmbedding, DeterministicEmbedding
from hotshop_agent.knowledge import chunk_documents, load_documents
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.providers.base import ModelChunk, ModelDelta, ModelTemporaryError
from hotshop_agent.providers.fake import FakeModel
from hotshop_agent.providers.openai_compatible import OpenAICompatibleChatTransport
from hotshop_agent.qdrant import KnowledgeIndexer, QdrantStore
from hotshop_agent.rag import RetrievalResult, SearchHit, build_evidence_context
from hotshop_agent.security import JwtVerifier
from hotshop_agent.service import AccessDeniedError, InvalidStateError
from hotshop_agent.state import HISTORY_MAX_TURNS, InMemoryStateStore


class RecordingModel(FakeModel):
    def __init__(self, delay_seconds: float = 0) -> None:
        super().__init__(delay_seconds=delay_seconds)
        self.prompts: list[str] = []

    async def stream(self, prompt: str) -> AsyncIterator[ModelChunk]:
        self.prompts.append(prompt)
        async for chunk in super().stream(prompt):
            yield chunk


async def finish_run(container: Any, principal: Any, run: Any) -> list[Any]:
    handle = await container.service.handle(run.id, principal)
    events = []
    while True:
        event = await asyncio.wait_for(handle.queue.get(), timeout=2)
        handle.queue.task_done()
        events.append(event)
        if event.type == "done":
            break
    await handle.task
    return events


@pytest.mark.asyncio
async def test_completed_conversation_is_available_to_next_turn_and_is_owner_isolated(
    settings: Settings, issue_token: Any
) -> None:
    provider = RecordingModel()
    container = build_container(settings, provider=provider)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    other = JwtVerifier(settings).verify(
        issue_token(IdentityKind.USER, claim_overrides={"sub": "43"}), IdentityKind.USER
    )
    try:
        session = await container.service.create_session(principal)
        first = await container.service.add_message(session.id, principal, "Remember the blue tea")
        run = await container.service.start_run(session.id, first.id, principal)
        await finish_run(container, principal, run)
        second = await container.service.add_message(
            session.id, principal, "Which color did I pick?"
        )
        await finish_run(
            container,
            principal,
            await container.service.start_run(session.id, second.id, principal),
        )
        assert "Remember the blue tea" in provider.prompts[-1]
        assert "HotShop FakeModel response." in provider.prompts[-1]
        assert provider.prompts[-1].endswith("Which color did I pick?")
        with pytest.raises(AccessDeniedError):
            await container.service.add_message(session.id, other, "Read their conversation")
        other_session = await container.service.create_session(other)
        assert await container.store.get_history(other_session.id) == []
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_same_message_is_idempotent_and_other_turn_is_serialized(
    settings: Settings, issue_token: Any
) -> None:
    provider = RecordingModel(delay_seconds=0.2)
    container = build_container(settings, provider=provider)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "hello")
        first, repeated = await asyncio.gather(
            container.service.start_run(session.id, message.id, principal),
            container.service.start_run(session.id, message.id, principal),
        )
        assert first.id == repeated.id
        second = await container.service.add_message(session.id, principal, "next")
        with pytest.raises(InvalidStateError):
            await container.service.start_run(session.id, second.id, principal)
        await finish_run(container, principal, first)
        assert provider.completed_calls == 1
        assert (await container.service.start_run(session.id, message.id, principal)).id == first.id
        await finish_run(
            container,
            principal,
            await container.service.start_run(session.id, second.id, principal),
        )
        assert provider.completed_calls == 2
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_immediate_cancel_releases_session_and_is_idempotent_after_handle_release(
    settings: Settings, issue_token: Any
) -> None:
    container = build_container(settings, provider=FakeModel(delay_seconds=1))
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "hello")
        run = await container.service.start_run(session.id, message.id, principal)
        cancelled = await asyncio.wait_for(container.service.cancel(run.id, principal), timeout=1)
        assert cancelled.state is RunState.CANCELLED
        assert await container.store.get_history(session.id) == []
        await container.service.release_handle(run.id)
        assert (await container.service.cancel(run.id, principal)).state is RunState.CANCELLED
        next_message = await container.service.add_message(session.id, principal, "next")
        next_run = await container.service.start_run(session.id, next_message.id, principal)
        await container.service.cancel(next_run.id, principal)
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_second_stream_subscriber_is_rejected_without_consuming_events(
    settings: Settings, issue_token: Any
) -> None:
    container = build_container(settings)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "hello")
        run = await container.service.start_run(session.id, message.id, principal)
        await container.service.handle(run.id, principal, subscribe=True)
        with pytest.raises(InvalidStateError):
            await container.service.handle(run.id, principal, subscribe=True)
        events = await finish_run(container, principal, run)
        assert events[-1].type == "done"
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_history_storage_and_prompt_are_bounded(settings: Settings, issue_token: Any) -> None:
    store = InMemoryStateStore()
    provider = RecordingModel()
    container = build_container(settings, store=store, provider=provider)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        for index in range(20):
            await store.append_turn(
                session.id,
                ConversationTurn(
                    message_id=str(index),
                    user="x" * 16000,
                    assistant="y" * 16000,
                ),
            )
        history = await store.get_history(session.id)
        assert len(history) == HISTORY_MAX_TURNS
        assert history[0].message_id == "8"
        message = await container.service.add_message(session.id, principal, "hello")
        await finish_run(
            container,
            principal,
            await container.service.start_run(session.id, message.id, principal),
        )
        assert len(provider.prompts[0]) < 26000
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_embedding_model_dimension_and_endpoint_change_index_identity() -> None:
    chunks = chunk_documents(
        load_documents(Path("knowledge"), trusted_tenant="hotshop"), chunk_size=800, overlap=80
    )
    assert chunks
    client = httpx.AsyncClient()
    store = QdrantStore(
        client,
        base_url="http://unused",
        alias="hotshop_knowledge",
        collection_prefix="hotshop_knowledge_v",
        timeout_seconds=1,
        max_retries=0,
    )

    def version(embedding: Any) -> str:
        indexer = KnowledgeIndexer(
            store, embedding, AgentMetrics(), tenant_id="hotshop", chunk_size=800, chunk_overlap=80
        )
        return indexer.validate(Path("knowledge"))[2]

    def bailian(model: str, endpoint: str = "https://example.test/v1") -> BailianEmbedding:
        return BailianEmbedding(
            client,
            base_url=endpoint,
            api_key="test-only",
            model=model,
            dimension=128,
            timeout_seconds=1,
        )

    baseline = version(DeterministicEmbedding(128))
    assert baseline == version(DeterministicEmbedding(128, max_items=2))
    assert baseline != version(DeterministicEmbedding(256))
    assert baseline != version(bailian("v4"))
    assert version(bailian("v4")) != version(bailian("v5"))
    assert version(bailian("v4")) != version(bailian("v4", "https://other.test/v1"))
    await client.aclose()


def test_citations_match_only_nonempty_context_after_budget_truncation() -> None:
    chunks = chunk_documents(
        load_documents(Path("knowledge"), trusted_tenant="hotshop"), chunk_size=800, overlap=80
    )
    result = RetrievalResult("hit", tuple(SearchHit(chunk, 1) for chunk in chunks), (), 0)
    context = build_evidence_context(result, max_chars=500)
    decoded = json.loads(context.serialized)
    assert len(context.serialized) <= 500
    assert decoded and len(decoded) < len(chunks)
    assert all(item["content"] for item in decoded)
    assert [item["chunkId"] for item in decoded] == [item.chunkId for item in context.citations]
    empty = build_evidence_context(result, max_chars=2)
    assert empty.serialized == "[]" and not empty.citations


@pytest.mark.asyncio
async def test_provider_early_eof_does_not_complete_successfully() -> None:
    content = 'data: {"choices":[{"delta":{"content":"partial answer"}}]}\n\n'
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, text=content))
    ) as client:
        provider = OpenAICompatibleChatTransport(
            client, base_url="https://example.test", api_key="test", model="fake"
        )
        received = []
        with pytest.raises(ModelTemporaryError, match="before completion"):
            async for item in provider.stream("question"):
                received.append(item)
        assert received == [ModelDelta("partial answer")]


@pytest.mark.asyncio
async def test_refusal_without_model_call_does_not_increment_provider_requests(
    settings: Settings, issue_token: Any
) -> None:
    container = build_container(settings.model_copy(update={"rag_enabled": True}))
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "用户 43 的订单状态")
        await finish_run(
            container,
            principal,
            await container.service.start_run(session.id, message.id, principal),
        )
        output = generate_latest(container.service.metrics.registry).decode()
        assert "hotshop_agent_provider_requests_total{" not in output
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_tool_summary_preserves_question_and_counts_actual_model_and_tool_calls(
    settings: Settings, issue_token: Any
) -> None:
    provider = RecordingModel()
    internal = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json={"items": [{"productId": "1", "name": "Blue tea"}]})
        )
    )
    container = build_container(settings, provider=provider, http_client=internal)
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    delegated = issue_token(IdentityKind.DELEGATION)
    credential = Credential(
        token=delegated, principal=JwtVerifier(settings).verify(delegated, IdentityKind.DELEGATION)
    )
    try:
        session = await container.service.create_session(principal)
        question = '{"tool":"search_products","arguments":{"keyword":"blue tea"}}'
        message = await container.service.add_message(session.id, principal, question)
        run = await container.service.start_run(
            session.id, message.id, principal, tool_credential=credential
        )
        await finish_run(container, principal, run)
        assert len(provider.prompts) == 2
        assert "Original user question" in provider.prompts[1]
        assert "blue tea" in provider.prompts[1]
        output = generate_latest(container.service.metrics.registry).decode()
        metric = "hotshop_agent_provider_requests_total"
        assert f'{metric}{{model="fake",outcome="success",provider="fake"}} 2.0' in output
        assert (
            'hotshop_agent_tool_calls_total{outcome="SUCCESS",tool="search_products"} 1.0' in output
        )
    finally:
        await container.close()


@pytest.mark.asyncio
async def test_simultaneous_cancel_requests_do_not_cancel_terminal_persistence(
    settings: Settings, issue_token: Any
) -> None:
    class SlowTerminalStore(InMemoryStateStore):
        async def put_run(self, run: AgentRun) -> None:
            if run.state is RunState.CANCELLED:
                await asyncio.sleep(0.05)
            await super().put_run(run)

    store = SlowTerminalStore()
    container = build_container(settings, store=store, provider=FakeModel(delay_seconds=1))
    principal = JwtVerifier(settings).verify(issue_token(IdentityKind.USER), IdentityKind.USER)
    try:
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "hello")
        run = await container.service.start_run(session.id, message.id, principal)
        outcomes = await asyncio.gather(
            container.service.cancel(run.id, principal),
            container.service.cancel(run.id, principal),
            container.service.shutdown(),
        )
        assert outcomes[0].state is RunState.CANCELLED
        assert outcomes[1].state is RunState.CANCELLED
        persisted = await store.get_run(run.id)
        assert persisted is not None and persisted.state is RunState.CANCELLED
    finally:
        await container.close()
