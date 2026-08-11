from __future__ import annotations

import json

import httpx
import pytest

from hotshop_agent.providers import (
    ModelDelta,
    ModelTemporaryError,
    ModelToolCall,
    ModelUsage,
    QwenModel,
)
from hotshop_agent.providers.openai_compatible import MAX_TOOL_CALL_BYTES


def sse(*contents: str, usage: bool = True) -> str:
    events = [
        f"data: {json.dumps({'choices': [{'delta': {'content': content}}]})}\n\n"
        for content in contents
    ]
    if usage:
        events.append('data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}\n\n')
    events.append("data: [DONE]\n\n")
    return "".join(events)


@pytest.mark.asyncio
async def test_qwen_adapter_streams_using_mock_http_only() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers["Authorization"]
        captured["body"] = json.loads(request.content)
        content = (
            'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'
            'data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}\n\n'
            "data: [DONE]\n\n"
        )
        return httpx.Response(200, text=content, headers={"content-type": "text/event-stream"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        model = QwenModel(
            client,
            base_url="https://qwen.invalid/v1",
            api_key="mock-only-key",
            model="qwen-mock",
            input_cost_per_million_usd=2,
            output_cost_per_million_usd=4,
        )
        chunks = [chunk async for chunk in model.stream("test")]

    assert chunks == [
        ModelDelta("hello"),
        ModelUsage(input_tokens=3, output_tokens=2, estimated_cost_usd=0.000014),
    ]
    assert captured["authorization"] == "Bearer mock-only-key"
    assert captured["body"] == {
        "model": "qwen-mock",
        "stream": True,
        "stream_options": {"include_usage": True},
        "messages": [{"role": "user", "content": "test"}],
    }


@pytest.mark.asyncio
async def test_qwen_adapter_classifies_retryable_status() -> None:
    transport = httpx.MockTransport(lambda _request: httpx.Response(503))
    async with httpx.AsyncClient(transport=transport) as client:
        model = QwenModel(
            client,
            base_url="https://qwen.invalid/v1",
            api_key="mock-only-key",
            model="qwen-mock",
        )
        with pytest.raises(ModelTemporaryError):
            _ = [chunk async for chunk in model.stream("test")]


@pytest.mark.asyncio
async def test_qwen_buffers_strict_complete_json_as_tool_call() -> None:
    content = sse(
        '{"tool":"search_products",',
        '"arguments":{"keyword":"tea","limit":5}}',
    )
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            200, text=content, headers={"content-type": "text/event-stream"}
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        model = QwenModel(
            client,
            base_url="https://qwen.invalid/v1",
            api_key="mock-only-key",
            model="qwen-mock",
        )
        chunks = [chunk async for chunk in model.stream("test")]
    assert chunks == [
        ModelToolCall("search_products", {"keyword": "tea", "limit": 5}),
        ModelUsage(3, 2, 0.0),
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "raw",
    (
        '{"tool":"search_products","arguments":{},"url":"https://example.test"}',
        ' {"tool":"search_products","arguments":{}}',
        '{"tool":"search_products","arguments":[]}',
        '{"tool":1,"arguments":{}}',
        '{"tool":"search_products"}',
    ),
)
async def test_qwen_non_strict_json_remains_plain_text(raw: str) -> None:
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            200, text=sse(raw), headers={"content-type": "text/event-stream"}
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        model = QwenModel(
            client,
            base_url="https://qwen.invalid/v1",
            api_key="mock-only-key",
            model="qwen-mock",
        )
        chunks = [chunk async for chunk in model.stream("test")]
    assert not any(isinstance(chunk, ModelToolCall) for chunk in chunks)
    assert "".join(chunk.text for chunk in chunks if isinstance(chunk, ModelDelta)) == raw


@pytest.mark.asyncio
async def test_qwen_tool_candidate_buffer_is_bounded() -> None:
    raw = "{" + "x" * MAX_TOOL_CALL_BYTES
    transport = httpx.MockTransport(
        lambda _request: httpx.Response(
            200, text=sse(raw), headers={"content-type": "text/event-stream"}
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        model = QwenModel(
            client,
            base_url="https://qwen.invalid/v1",
            api_key="mock-only-key",
            model="qwen-mock",
        )
        chunks = [chunk async for chunk in model.stream("test")]
    assert not any(isinstance(chunk, ModelToolCall) for chunk in chunks)
    assert "".join(chunk.text for chunk in chunks if isinstance(chunk, ModelDelta)) == raw
