from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from typing import Any

import httpx
import pytest

from hotshop_agent.graph import USER_POLICY
from hotshop_agent.providers import (
    DeepSeekModel,
    FakeModel,
    ModelDelta,
    ModelPermanentError,
    ModelProvider,
    ModelTemporaryError,
    ModelToolCall,
    ModelUsage,
    QwenModel,
)
from hotshop_agent.providers.openai_compatible import MAX_RESPONSE_BYTES
from hotshop_agent.registry import ADMIN_TOOLS, USER_TOOLS

ProviderFactory = Callable[[httpx.AsyncClient], ModelProvider]


def real_provider(
    name: str, client: httpx.AsyncClient, *, key: str = "contract-secret"
) -> ModelProvider:
    if name == "deepseek":
        return DeepSeekModel(client, api_key=key, model="deepseek-v4-flash")
    return QwenModel(
        client,
        base_url="https://qwen.invalid/v1",
        api_key=key,
        model="qwen-plus",
    )


def sse(*contents: str, reasoning: str | None = None) -> str:
    events: list[str] = []
    if reasoning is not None:
        events.append(
            f"data: {json.dumps({'choices': [{'delta': {'reasoning_content': reasoning}}]})}\n\n"
        )
    events.extend(
        f"data: {json.dumps({'choices': [{'delta': {'content': content}}]})}\n\n"
        for content in contents
    )
    events.append('data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}\n\n')
    events.append("data: [DONE]\n\n")
    return "".join(events)


@pytest.mark.asyncio
@pytest.mark.parametrize("provider_name", ("fake", "deepseek", "qwen"))
async def test_provider_contract_delta_usage_tool_and_fixed_registry(provider_name: str) -> None:
    tool_json = '{"tool":"search_products","arguments":{"keyword":"tea","limit":5}}'
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            200,
            text=sse(tool_json),
            headers={"content-type": "text/event-stream"},
        )
    )
    before = (USER_TOOLS.tools, ADMIN_TOOLS.tools)
    async with httpx.AsyncClient(transport=transport) as client:
        if provider_name == "fake":
            provider: ModelProvider = FakeModel()
            prompt = f"{USER_POLICY}\n\nUser message:\n{tool_json}"
        else:
            provider = real_provider(provider_name, client)
            prompt = "select a fixed JSON tool"
        chunks = [chunk async for chunk in provider.stream(prompt)]

    assert chunks[0] == ModelToolCall("search_products", {"keyword": "tea", "limit": 5})
    assert isinstance(chunks[-1], ModelUsage)
    assert provider.capabilities.provider_name == provider_name
    assert provider.capabilities.streaming
    assert provider.capabilities.custom_json_tool_selection
    assert (USER_TOOLS.tools, ADMIN_TOOLS.tools) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("provider_name", ("fake", "deepseek", "qwen"))
@pytest.mark.parametrize(
    ("status_code", "error_type"),
    (
        (408, ModelTemporaryError),
        (429, ModelTemporaryError),
        (503, ModelTemporaryError),
        (400, ModelPermanentError),
    ),
)
async def test_provider_contract_normalizes_errors_without_key_leak(
    provider_name: str,
    status_code: int,
    error_type: type[Exception],
) -> None:
    credential = "provider-contract-sensitive-value"
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(status_code))
    ) as client:
        if provider_name == "fake":
            failure = error_type("bounded provider failure")
            provider: ModelProvider = FakeModel(failure=failure)  # type: ignore[arg-type]
        else:
            provider = real_provider(provider_name, client, key=credential)
        with pytest.raises(error_type) as caught:
            _ = [chunk async for chunk in provider.stream("private prompt")]
    assert credential not in str(caught.value)
    assert "private prompt" not in str(caught.value)
    assert credential not in repr(provider)


@pytest.mark.asyncio
@pytest.mark.parametrize("provider_name", ("deepseek", "qwen"))
async def test_vendor_request_fields_and_reasoning_are_isolated(provider_name: str) -> None:
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers["Authorization"]
        captured["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            text=sse("safe", reasoning="hidden-chain-of-thought"),
            headers={"content-type": "text/event-stream"},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = real_provider(provider_name, client)
        chunks = [chunk async for chunk in provider.stream("question")]

    assert captured["authorization"] == "Bearer contract-secret"
    body = captured["body"]
    assert isinstance(body, dict)
    if provider_name == "deepseek":
        assert body["thinking"] == {"type": "disabled"}
        assert provider.capabilities.reasoning_content_handling == "ignored"
    else:
        assert "thinking" not in body
        assert provider.capabilities.reasoning_content_handling == "not_provided"
    visible = "".join(chunk.text for chunk in chunks if isinstance(chunk, ModelDelta))
    assert visible == "safe"
    assert "hidden-chain-of-thought" not in visible


class BlockingStream(httpx.AsyncByteStream):
    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.closed = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        self.started.set()
        await asyncio.Event().wait()
        if False:
            yield b""

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.asyncio
@pytest.mark.parametrize("provider_name", ("fake", "deepseek", "qwen"))
async def test_provider_contract_cancellation_closes_stream(provider_name: str) -> None:
    stream = BlockingStream()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200, stream=stream, headers={"content-type": "text/event-stream"}
            )
        )
    ) as client:
        if provider_name == "fake":
            fake = FakeModel(delay_seconds=10)
            provider: ModelProvider = fake
        else:
            provider = real_provider(provider_name, client)

        async def consume() -> None:
            _ = [chunk async for chunk in provider.stream("cancel")]

        task = asyncio.create_task(consume())
        if provider_name == "fake":
            await asyncio.sleep(0)
            assert fake.active_calls == 1
        else:
            await stream.started.wait()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    if provider_name == "fake":
        assert fake.active_calls == 0
    else:
        assert stream.closed


@pytest.mark.asyncio
async def test_openai_compatible_response_size_is_bounded() -> None:
    oversized = sse("x" * MAX_RESPONSE_BYTES)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200, text=oversized, headers={"content-type": "text/event-stream"}
            )
        )
    ) as client:
        provider = DeepSeekModel(client, api_key="secret")
        with pytest.raises(ModelPermanentError, match="exceeded"):
            _ = [chunk async for chunk in provider.stream("question")]
