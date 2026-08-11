from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import httpx

from hotshop_agent.embeddings.base import EmbeddingProvider
from hotshop_agent.knowledge import (
    DocumentType,
    KnowledgeChunk,
    Visibility,
    chunk_documents,
    knowledge_version,
    load_documents,
)
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.rag import SearchHit

_SAFE_NAME = re.compile(r"^[a-z][a-z0-9_-]{0,62}$")


class QdrantUnavailable(Exception):
    pass


@dataclass(frozen=True)
class IndexStatus:
    alias: str
    collection: str | None
    version: str | None
    points: int


class QdrantStore:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        base_url: str,
        alias: str,
        collection_prefix: str,
        timeout_seconds: float,
        max_retries: int,
    ) -> None:
        if not _SAFE_NAME.fullmatch(alias) or not _SAFE_NAME.fullmatch(collection_prefix):
            raise ValueError("Qdrant alias and prefix must be code-owned safe names")
        self._client = client
        self._base_url = base_url.rstrip("/")
        self.alias = alias
        self.collection_prefix = collection_prefix
        self._timeout = timeout_seconds
        self._max_retries = max_retries

    async def ready(self) -> bool:
        try:
            response = await self._request("GET", "/healthz")
            return response.status_code == 200
        except QdrantUnavailable:
            return False

    async def search(
        self,
        vector: list[float],
        *,
        tenant_id: str,
        visibilities: tuple[Visibility, ...],
        document_types: tuple[DocumentType, ...],
        now: datetime,
        limit: int,
    ) -> list[SearchHit]:
        epoch = int(now.timestamp())
        body = {
            "vector": vector,
            "limit": limit,
            "with_payload": True,
            "with_vector": False,
            "filter": {
                "must": [
                    {"key": "tenantId", "match": {"value": tenant_id}},
                    {
                        "key": "visibility",
                        "match": {"any": [visibility.value for visibility in visibilities]},
                    },
                    {
                        "key": "documentType",
                        "match": {"any": [kind.value for kind in document_types]},
                    },
                    {"key": "effectiveFromEpoch", "range": {"lte": epoch}},
                    {"key": "effectiveUntilEpoch", "range": {"gte": epoch}},
                ]
            },
        }
        response = await self._request(
            "POST", f"/collections/{self.alias}/points/search", json=body
        )
        if response.status_code != 200:
            raise QdrantUnavailable("Qdrant search failed")
        try:
            rows = response.json()["result"]
            if not isinstance(rows, list):
                raise TypeError
            return [
                SearchHit(
                    KnowledgeChunk.model_validate(row["payload"]),
                    float(row["score"]),
                )
                for row in rows
            ]
        except (KeyError, TypeError, ValueError) as exc:
            raise QdrantUnavailable("Qdrant search response is invalid") from exc

    async def collection_for_alias(self) -> str | None:
        response = await self._request("GET", "/aliases")
        if response.status_code != 200:
            raise QdrantUnavailable("Qdrant alias lookup failed")
        try:
            aliases = response.json()["result"]["aliases"]
            for item in aliases:
                if item.get("alias_name") == self.alias:
                    value = item.get("collection_name")
                    return value if isinstance(value, str) else None
            return None
        except (KeyError, TypeError, ValueError) as exc:
            raise QdrantUnavailable("Qdrant alias response is invalid") from exc

    async def count(self, collection: str) -> int:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        response = await self._request(
            "POST", f"/collections/{collection}/points/count", json={"exact": True}
        )
        if response.status_code != 200:
            raise QdrantUnavailable("Qdrant count failed")
        try:
            return int(response.json()["result"]["count"])
        except (KeyError, TypeError, ValueError) as exc:
            raise QdrantUnavailable("Qdrant count response is invalid") from exc

    async def status(self) -> IndexStatus:
        collection = await self.collection_for_alias()
        if collection is None:
            return IndexStatus(self.alias, None, None, 0)
        version = self.version_for_collection(collection)
        return IndexStatus(self.alias, collection, version, await self.count(collection))

    def version_for_collection(self, collection: str) -> str:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        suffix = collection.removeprefix(f"{self.collection_prefix}_")
        return suffix.split("_", maxsplit=1)[0]

    async def create_collection(self, collection: str, dimension: int) -> None:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        response = await self._request(
            "PUT",
            f"/collections/{collection}",
            json={
                "vectors": {"size": dimension, "distance": "Cosine"},
                "on_disk_payload": True,
            },
        )
        if response.status_code not in {200, 201}:
            raise QdrantUnavailable("Qdrant collection creation failed")
        for field, schema in (
            ("tenantId", "keyword"),
            ("visibility", "keyword"),
            ("documentType", "keyword"),
            ("effectiveFromEpoch", "integer"),
            ("effectiveUntilEpoch", "integer"),
        ):
            indexed = await self._request(
                "PUT",
                f"/collections/{collection}/index",
                params={"wait": "true"},
                json={"field_name": field, "field_schema": schema},
            )
            if indexed.status_code not in {200, 201}:
                raise QdrantUnavailable("Qdrant payload index creation failed")

    async def upsert(self, collection: str, rows: list[tuple[KnowledgeChunk, list[float]]]) -> None:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        for offset in range(0, len(rows), 64):
            points = [
                {
                    "id": chunk.point_id,
                    "vector": vector,
                    "payload": chunk.model_dump(mode="json"),
                }
                for chunk, vector in rows[offset : offset + 64]
            ]
            response = await self._request(
                "PUT",
                f"/collections/{collection}/points",
                params={"wait": "true"},
                json={"points": points},
            )
            if response.status_code not in {200, 201}:
                raise QdrantUnavailable("Qdrant upsert failed")

    async def switch_alias(self, collection: str, previous: str | None) -> None:
        actions: list[dict[str, Any]] = []
        if previous is not None:
            actions.append({"delete_alias": {"alias_name": self.alias}})
        actions.append({"create_alias": {"collection_name": collection, "alias_name": self.alias}})
        response = await self._request("POST", "/collections/aliases", json={"actions": actions})
        if response.status_code != 200:
            raise QdrantUnavailable("Qdrant alias switch failed")

    async def delete_collection(self, collection: str) -> None:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        response = await self._request("DELETE", f"/collections/{collection}")
        if response.status_code not in {200, 404}:
            raise QdrantUnavailable("Qdrant collection cleanup failed")

    async def collection_exists(self, collection: str) -> bool:
        if not self._trusted_collection(collection):
            raise ValueError("untrusted collection name")
        response = await self._request("GET", f"/collections/{collection}")
        if response.status_code == 404:
            return False
        if response.status_code != 200:
            raise QdrantUnavailable("Qdrant collection lookup failed")
        return True

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        for attempt in range(self._max_retries + 1):
            try:
                response = await self._client.request(
                    method,
                    f"{self._base_url}{path}",
                    timeout=self._timeout,
                    **kwargs,
                )
                if response.status_code not in {429, 500, 502, 503, 504}:
                    return response
            except (httpx.TimeoutException, httpx.TransportError):
                response = None
            if attempt < self._max_retries:
                await asyncio.sleep(0.05 * (2**attempt))
        raise QdrantUnavailable("Qdrant is unavailable")

    def _trusted_collection(self, value: str) -> bool:
        return value.startswith(f"{self.collection_prefix}_") and bool(_SAFE_NAME.fullmatch(value))


class KnowledgeIndexer:
    def __init__(
        self,
        store: QdrantStore,
        embedding: EmbeddingProvider,
        metrics: AgentMetrics,
        *,
        tenant_id: str,
        chunk_size: int,
        chunk_overlap: int,
    ) -> None:
        self.store = store
        self.embedding = embedding
        self.metrics = metrics
        self.tenant_id = tenant_id
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap

    def validate(self, directory: Any) -> tuple[list[Any], list[KnowledgeChunk], str]:
        documents = load_documents(directory, trusted_tenant=self.tenant_id)
        chunks = chunk_documents(
            documents,
            chunk_size=self.chunk_size,
            overlap=self.chunk_overlap,
        )
        return documents, chunks, knowledge_version(chunks)

    async def rebuild(self, directory: Any) -> IndexStatus:
        documents, chunks, version = self.validate(directory)
        version_id = version[:16]
        collection = f"{self.store.collection_prefix}_{version_id}"
        previous = await self.store.collection_for_alias()
        if (
            previous is not None
            and self.store.version_for_collection(previous) == version_id
            and await self.store.count(previous) == len(chunks)
        ):
            self.metrics.index_documents.set(len(documents))
            self.metrics.index_chunks.set(len(chunks))
            return IndexStatus(self.store.alias, previous, version_id, len(chunks))

        if previous == collection:
            collection = f"{collection}_repair"

        created = False
        switched = False
        try:
            if await self.store.collection_exists(collection):
                await self.store.delete_collection(collection)
            await self.store.create_collection(collection, self.embedding.dimension)
            created = True
            vectors: list[list[float]] = []
            batch_size = self.embedding.max_batch_size
            for offset in range(0, len(chunks), batch_size):
                vectors.extend(
                    await self.embedding.embed_documents(
                        [chunk.content for chunk in chunks[offset : offset + batch_size]]
                    )
                )
            await self.store.upsert(collection, list(zip(chunks, vectors, strict=True)))
            if await self.store.count(collection) != len(chunks):
                raise QdrantUnavailable("Qdrant rebuild count mismatch")
            await self.store.switch_alias(collection, previous)
            switched = True
            if previous is not None and previous != collection:
                try:
                    await self.store.delete_collection(previous)
                except QdrantUnavailable:
                    logging.getLogger(__name__).warning(
                        "inactive knowledge collection cleanup failed",
                        extra={
                            "event": "agent.rag.rebuild.cleanup",
                            "outcome": "failure",
                            "parameterSummary": "inactive_collection",
                        },
                    )
            self.metrics.rebuilds.labels("success").inc()
            self.metrics.index_documents.set(len(documents))
            self.metrics.index_chunks.set(len(chunks))
            logging.getLogger(__name__).info(
                "knowledge rebuild completed",
                extra={
                    "event": "agent.rag.rebuild",
                    "outcome": "success",
                    "parameterSummary": f"documents={len(documents)},chunks={len(chunks)}",
                },
            )
            return IndexStatus(self.store.alias, collection, version_id, len(chunks))
        except Exception:
            self.metrics.rebuilds.labels("failure").inc()
            if created and not switched and previous != collection:
                try:
                    await self.store.delete_collection(collection)
                except QdrantUnavailable:
                    pass
            raise
