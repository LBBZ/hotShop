from __future__ import annotations

import json

import httpx
import pytest

from hotshop_agent.providers import ModelDelta, ModelTemporaryError, ModelUsage, QwenModel


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
