from __future__ import annotations

import logging

import httpx

from hotshop_agent.embeddings.base import EmbeddingError, validate_inputs, validate_vectors


class BailianEmbedding:
    name = "bailian"

    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        base_url: str,
        api_key: str,
        model: str,
        dimension: int,
        timeout_seconds: float,
        max_items: int = 10,
        max_chars: int = 8_000,
    ) -> None:
        if not api_key.strip():
            raise ValueError("Bailian embedding key is required")
        self._client = client
        self._url = f"{base_url.rstrip('/')}/embeddings"
        self._api_key = api_key
        self._model = model
        self.dimension = dimension
        self._timeout = timeout_seconds
        self.max_batch_size = min(max_items, 10)
        self._max_chars = max_chars

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        validate_inputs(texts, max_items=self.max_batch_size, max_chars=self._max_chars)
        try:
            response = await self._client.post(
                self._url,
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={"model": self._model, "input": texts, "dimensions": self.dimension},
                timeout=self._timeout,
            )
            if response.status_code == 429:
                raise EmbeddingError("embedding provider is rate limited")
            if response.status_code >= 400:
                raise EmbeddingError("embedding provider rejected the request")
            body = response.json()
            data = body.get("data") if isinstance(body, dict) else None
            if not isinstance(data, list):
                raise EmbeddingError("embedding response is invalid")
            indices: list[int] = []
            for item in data:
                index = item.get("index") if isinstance(item, dict) else None
                if isinstance(index, bool) or not isinstance(index, int):
                    raise EmbeddingError("embedding response index is invalid")
                indices.append(index)
            if sorted(indices) != list(range(len(texts))):
                raise EmbeddingError("embedding response index is invalid")
            ordered = sorted(data, key=lambda item: item["index"])
            vectors = [
                item.get("embedding") if isinstance(item, dict) else None for item in ordered
            ]
            result = validate_vectors(
                vectors,
                expected_count=len(texts),
                dimension=self.dimension,
            )
            logging.getLogger(__name__).info(
                "embedding request completed",
                extra={
                    "event": "agent.embedding.request",
                    "outcome": "success",
                    "provider": self.name,
                    "parameterSummary": f"count={len(texts)}",
                },
            )
            return result
        except EmbeddingError:
            raise
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            raise EmbeddingError("embedding provider is unavailable") from exc
        except (ValueError, TypeError, KeyError) as exc:
            raise EmbeddingError("embedding response is invalid") from exc

    async def embed_query(self, text: str) -> list[float]:
        return (await self.embed_documents([text]))[0]
