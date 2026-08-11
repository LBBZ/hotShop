from __future__ import annotations

import json
import os
import shutil
import time
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest

from hotshop_agent.domain import IdentityKind
from hotshop_agent.embeddings import DeterministicEmbedding, EmbeddingError
from hotshop_agent.knowledge import DocumentType, Visibility
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.qdrant import KnowledgeIndexer, QdrantStore, QdrantUnavailable
from hotshop_agent.rag import allowed_visibilities

pytestmark = pytest.mark.qdrant


def qdrant_store(client: httpx.AsyncClient, *, retries: int = 1) -> QdrantStore:
    return QdrantStore(
        client,
        base_url=os.environ.get("AGENT_QDRANT_URL", "http://qdrant:6333"),
        alias="hotshop_knowledge",
        collection_prefix="hotshop_knowledge_v",
        timeout_seconds=1,
        max_retries=retries,
    )


@pytest.mark.asyncio
async def test_real_qdrant_rebuild_filter_atomic_update_and_delete(tmp_path: Path) -> None:
    async with httpx.AsyncClient() as client:
        store = qdrant_store(client)
        assert await store.ready(), "real Qdrant is required for the full pytest gate"
        embedding = DeterministicEmbedding(128)
        indexer = KnowledgeIndexer(
            store,
            embedding,
            AgentMetrics(),
            tenant_id="hotshop",
            chunk_size=800,
            chunk_overlap=80,
        )
        initial = await indexer.rebuild(Path("knowledge"))
        repeated = await indexer.rebuild(Path("knowledge"))
        assert repeated == initial

        user_hits = await store.search(
            await embedding.embed_query("管理员静态运营说明常见问题"),
            tenant_id="hotshop",
            visibilities=allowed_visibilities(IdentityKind.USER),
            document_types=(DocumentType.FAQ,),
            now=datetime.now(UTC),
            limit=10,
        )
        admin_hits = await store.search(
            await embedding.embed_query("登录用户支持渠道常见问题"),
            tenant_id="hotshop",
            visibilities=allowed_visibilities(IdentityKind.ADMINISTRATOR),
            document_types=(DocumentType.FAQ,),
            now=datetime.now(UTC),
            limit=10,
        )
        wrong_tenant = await store.search(
            await embedding.embed_query("如何保护账户"),
            tenant_id="attacker",
            visibilities=(Visibility.PUBLIC, Visibility.USER, Visibility.ADMIN),
            document_types=(DocumentType.FAQ,),
            now=datetime.now(UTC),
            limit=10,
        )
        assert all(hit.chunk.visibility is not Visibility.ADMIN for hit in user_hits)
        assert all(hit.chunk.visibility is not Visibility.USER for hit in admin_hits)
        assert not wrong_tenant

        changed = tmp_path / "changed"
        changed.mkdir()
        for source in Path("knowledge").glob("*.json"):
            shutil.copy2(source, changed / source.name)
        faq = changed / "faq-account.json"
        body = json.loads(faq.read_text(encoding="utf-8"))
        body["documentVersion"] = "2.0.0"
        body["content"] += " 新版本内容。"
        faq.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        (changed / "campaign-rules.json").unlink()
        updated = await indexer.rebuild(changed)
        assert updated.collection != initial.collection
        assert await store.collection_for_alias() == updated.collection
        assert initial.collection is not None
        assert not await store.collection_exists(initial.collection)
        updated_hits = await store.search(
            await embedding.embed_query("如何保护账户"),
            tenant_id="hotshop",
            visibilities=(Visibility.PUBLIC, Visibility.USER),
            document_types=(DocumentType.FAQ,),
            now=datetime.now(UTC),
            limit=10,
        )
        campaign_hits = await store.search(
            await embedding.embed_query("秒杀活动规则"),
            tenant_id="hotshop",
            visibilities=(Visibility.PUBLIC, Visibility.USER),
            document_types=(DocumentType.CAMPAIGN_RULE,),
            now=datetime.now(UTC),
            limit=10,
        )
        assert any(
            hit.chunk.documentId == "faq-account-security" and hit.chunk.documentVersion == "2.0.0"
            for hit in updated_hits
        )
        assert all(
            hit.chunk.documentVersion != "1.0.0"
            for hit in updated_hits
            if hit.chunk.documentId == "faq-account-security"
        )
        assert not campaign_hits

        class FailingEmbedding:
            name = "failing"
            dimension = 128
            max_batch_size = 10

            async def embed_documents(self, texts: list[str]) -> list[list[float]]:
                del texts
                raise EmbeddingError("bounded failure")

            async def embed_query(self, text: str) -> list[float]:
                del text
                raise EmbeddingError("bounded failure")

        failed_source = tmp_path / "failed"
        shutil.copytree(changed, failed_source)
        failed_faq = failed_source / "faq-account.json"
        failed_body = json.loads(failed_faq.read_text(encoding="utf-8"))
        failed_body["documentVersion"] = "3.0.0"
        failed_faq.write_text(json.dumps(failed_body, ensure_ascii=False), encoding="utf-8")
        failing = KnowledgeIndexer(
            store,
            FailingEmbedding(),
            AgentMetrics(),
            tenant_id="hotshop",
            chunk_size=800,
            chunk_overlap=80,
        )
        with pytest.raises(EmbeddingError):
            await failing.rebuild(failed_source)
        assert await store.collection_for_alias() == updated.collection

        restored = await indexer.rebuild(Path("knowledge"))
        assert await store.collection_for_alias() == restored.collection


@pytest.mark.asyncio
async def test_qdrant_unavailable_has_finite_retry_and_bounded_error() -> None:
    attempts = 0

    def unavailable(_request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectError("secret response body")

    async with httpx.AsyncClient(transport=httpx.MockTransport(unavailable)) as client:
        store = QdrantStore(
            client,
            base_url="http://qdrant.invalid",
            alias="hotshop_knowledge",
            collection_prefix="hotshop_knowledge_v",
            timeout_seconds=0.1,
            max_retries=1,
        )
        started = time.monotonic()
        with pytest.raises(QdrantUnavailable) as caught:
            await store.status()
    assert attempts == 2
    assert time.monotonic() - started < 1
    assert "secret response body" not in str(caught.value)


@pytest.mark.asyncio
async def test_indexer_uses_provider_batch_capability_for_more_than_100_chunks(
    tmp_path: Path,
) -> None:
    source = tmp_path / "many"
    source.mkdir()
    for index in range(105):
        (source / f"faq-{index:03d}.json").write_text(
            json.dumps(
                {
                    "tenantId": "hotshop",
                    "documentId": f"faq-batch-{index:03d}",
                    "documentVersion": "1.0.0",
                    "documentType": "FAQ",
                    "visibility": "PUBLIC",
                    "title": f"Batch FAQ {index}",
                    "source": f"https://docs.hotshop.local/faq/batch-{index}",
                    "locale": "en-US",
                    "effectiveFrom": None,
                    "effectiveUntil": None,
                    "content": (
                        f"Static help topic number {index} for deterministic batch indexing."
                    ),
                }
            ),
            encoding="utf-8",
        )

    class RecordingEmbedding:
        name = "recording"
        dimension = 32
        max_batch_size = 10

        def __init__(self) -> None:
            self.calls: list[int] = []
            self.delegate = DeterministicEmbedding(32, max_items=10)

        async def embed_documents(self, texts: list[str]) -> list[list[float]]:
            self.calls.append(len(texts))
            return await self.delegate.embed_documents(texts)

        async def embed_query(self, text: str) -> list[float]:
            return await self.delegate.embed_query(text)

    async with httpx.AsyncClient() as client:
        store = qdrant_store(client)
        embedding = RecordingEmbedding()
        indexer = KnowledgeIndexer(
            store,
            embedding,
            AgentMetrics(),
            tenant_id="hotshop",
            chunk_size=800,
            chunk_overlap=80,
        )
        try:
            status = await indexer.rebuild(source)
            assert status.points == 105
            assert embedding.calls == [10] * 10 + [5]
        finally:
            await KnowledgeIndexer(
                store,
                DeterministicEmbedding(128),
                AgentMetrics(),
                tenant_id="hotshop",
                chunk_size=800,
                chunk_overlap=80,
            ).rebuild(Path("knowledge"))
