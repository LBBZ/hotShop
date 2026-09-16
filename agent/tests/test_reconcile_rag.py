from __future__ import annotations

import json
import logging
from pathlib import Path

import httpx
import pytest

from hotshop_agent.domain import IdentityKind
from hotshop_agent.embeddings import DeterministicEmbedding
from hotshop_agent.knowledge import DocumentType, chunk_documents, load_documents
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.observability import JsonFormatter, reset_request_id, set_request_id
from hotshop_agent.qdrant import QdrantStore
from hotshop_agent.rag import InMemoryVectorSearch, RagRetriever


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("question", "outcome"),
    [
        ("售后申请应从哪里发起？", "hit"),
        ("How does the after-sales return policy work?", "hit"),
        # Original TASK-21 failure retained: feature hashing is not semantic paraphrase search.
        ("售后退换申请应该怎么做？", "empty"),
    ],
)
async def test_default_corpus_direct_questions_and_original_paraphrase(
    question: str, outcome: str
) -> None:
    embedding = DeterministicEmbedding(128)
    chunks = chunk_documents(
        load_documents(Path("knowledge"), trusted_tenant="hotshop"), chunk_size=800, overlap=80
    )
    vectors = await embedding.embed_documents([chunk.content for chunk in chunks])
    retriever = RagRetriever(
        InMemoryVectorSearch(list(zip(chunks, vectors, strict=True))),
        embedding,
        AgentMetrics(),
        tenant_id="hotshop",
        top_k=3,
        minimum_score=0.15,
    )
    result = await retriever.retrieve(
        question, identity=IdentityKind.USER, document_types=(DocumentType.AFTER_SALES_POLICY,)
    )
    assert result.outcome == outcome
    if outcome == "hit":
        assert result.citations
        for citation, hit in zip(result.citations, result.hits, strict=True):
            assert citation.documentId == "after-sales-general"
            assert citation.version == "1.0.0"
            assert citation.chunkId == hit.chunk.chunkId
            assert citation.source == "https://docs.hotshop.local/policies/after-sales"
            assert "售后申请应从订单详情页发起" in hit.chunk.content
    else:
        assert not result.hits and not result.citations


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure,category",
    [
        ("connect", "qdrant_connect"),
        ("timeout", "qdrant_timeout"),
        ("http", "qdrant_http"),
        ("response", "qdrant_response"),
        ("runtime", "retrieval_runtime"),
    ],
)
async def test_unavailable_diagnostics_are_correlated_and_content_free(
    caplog: pytest.LogCaptureFixture, failure: str, category: str
) -> None:
    private = "private-question-body api_key=do-not-log"

    def handler(request: httpx.Request) -> httpx.Response:
        if failure == "connect":
            raise httpx.ConnectError(private)
        if failure == "timeout":
            raise httpx.ReadTimeout(private)
        if failure == "runtime":
            raise RuntimeError(private)
        return httpx.Response(503 if failure == "http" else 200, json={"result": private})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        store = QdrantStore(
            client,
            base_url="http://qdrant:6333",
            alias="hotshop_knowledge",
            collection_prefix="hotshop_knowledge_v",
            timeout_seconds=1,
            max_retries=0,
        )
        retriever = RagRetriever(
            store,
            DeterministicEmbedding(128),
            AgentMetrics(),
            tenant_id="hotshop",
            top_k=3,
            minimum_score=0.15,
        )
        token = set_request_id("reconcile-request")
        try:
            with caplog.at_level(logging.INFO):
                result = await retriever.retrieve(
                    private,
                    identity=IdentityKind.USER,
                    document_types=(DocumentType.AFTER_SALES_POLICY,),
                    run_id="reconcile-run",
                )
            records = [
                r for r in caplog.records if getattr(r, "event", "") == "agent.rag.retrieval"
            ]
            payload = json.loads(JsonFormatter("agent", "test").format(records[-1]))
        finally:
            reset_request_id(token)
    assert result.outcome == "unavailable"
    assert not result.hits and not result.citations
    assert payload["requestId"] == "reconcile-request"
    assert payload["runId"] == "reconcile-run"
    assert payload["errorType"] == category
    assert "private-question" not in json.dumps(payload)
    assert "do-not-log" not in json.dumps(payload)
