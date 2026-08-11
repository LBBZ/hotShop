from __future__ import annotations

import math

import httpx
import pytest

from hotshop_agent.embeddings import BailianEmbedding, DeterministicEmbedding, EmbeddingError


@pytest.mark.asyncio
async def test_deterministic_embedding_is_stable_finite_and_fixed_dimension() -> None:
    first = DeterministicEmbedding(64)
    second = DeterministicEmbedding(64)
    vectors = await first.embed_documents(["售后政策", "秒杀活动规则"])
    assert vectors == await second.embed_documents(["售后政策", "秒杀活动规则"])
    assert all(len(vector) == 64 for vector in vectors)
    assert all(math.isfinite(value) for vector in vectors for value in vector)


@pytest.mark.asyncio
async def test_embedding_input_limits_are_enforced() -> None:
    provider = DeterministicEmbedding(32, max_items=2, max_chars=4)
    with pytest.raises(EmbeddingError):
        await provider.embed_documents([])
    with pytest.raises(EmbeddingError):
        await provider.embed_documents(["1", "2", "3"])
    with pytest.raises(EmbeddingError):
        await provider.embed_query("12345")


@pytest.mark.asyncio
async def test_bailian_auth_batch_and_ordered_response() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers["Authorization"]
        seen["body"] = request.read().decode()
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": [0.0, 1.0]},
                    {"index": 0, "embedding": [1.0, 0.0]},
                ]
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        provider = BailianEmbedding(
            client,
            base_url="https://example.invalid/v1",
            api_key="task17-secret-key",
            model="embedding-test",
            dimension=2,
            timeout_seconds=1,
        )
        assert await provider.embed_documents(["first", "second"]) == [[1.0, 0.0], [0.0, 1.0]]
    assert seen["authorization"] == "Bearer task17-secret-key"
    assert '"input":["first","second"]' in str(seen["body"])


@pytest.mark.asyncio
async def test_bailian_text_embedding_v4_caps_batches_at_ten() -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(500))
    ) as client:
        provider = BailianEmbedding(
            client,
            base_url="https://example.invalid/v1",
            api_key="secret",
            model="text-embedding-v4",
            dimension=2,
            timeout_seconds=0.1,
            max_items=100,
        )
        assert provider.max_batch_size == 10
        with pytest.raises(EmbeddingError, match="batch size"):
            await provider.embed_documents([f"item-{index}" for index in range(11)])


@pytest.mark.parametrize(
    ("response", "message"),
    [
        (httpx.Response(429), "rate limited"),
        (httpx.Response(400), "rejected"),
        (httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0]}]}), "dimension"),
        (
            httpx.Response(
                200,
                content=b'{"data":[{"index":0,"embedding":[NaN,0.0]}]}',
            ),
            "value",
        ),
        (httpx.Response(200, json={"unexpected": []}), "invalid"),
    ],
)
@pytest.mark.asyncio
async def test_bailian_rejects_rate_limit_and_invalid_responses(
    response: httpx.Response, message: str
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: response)
    ) as client:
        provider = BailianEmbedding(
            client,
            base_url="https://example.invalid/v1",
            api_key="secret",
            model="test",
            dimension=2,
            timeout_seconds=0.1,
        )
        with pytest.raises(EmbeddingError, match=message):
            await provider.embed_query("safe input")


@pytest.mark.asyncio
async def test_bailian_timeout_is_bounded_and_does_not_disclose_key() -> None:
    def timeout(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("provider body containing task17-secret")

    async with httpx.AsyncClient(transport=httpx.MockTransport(timeout)) as client:
        provider = BailianEmbedding(
            client,
            base_url="https://example.invalid/v1",
            api_key="task17-secret",
            model="test",
            dimension=2,
            timeout_seconds=0.1,
        )
        with pytest.raises(EmbeddingError) as caught:
            await provider.embed_query("private document body")
    assert "task17-secret" not in str(caught.value)
    assert "private document body" not in str(caught.value)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "indices",
    ([0, 0], [0, 2], [-1, 0]),
)
async def test_bailian_rejects_duplicate_missing_and_out_of_range_indices(
    indices: list[int],
) -> None:
    response = httpx.Response(
        200,
        json={"data": [{"index": index, "embedding": [1.0, 0.0]} for index in indices]},
    )
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: response)
    ) as client:
        provider = BailianEmbedding(
            client,
            base_url="https://example.invalid/v1",
            api_key="secret",
            model="text-embedding-v4",
            dimension=2,
            timeout_seconds=0.1,
        )
        with pytest.raises(EmbeddingError, match="index"):
            await provider.embed_documents(["first", "second"])
