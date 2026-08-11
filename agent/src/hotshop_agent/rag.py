from __future__ import annotations

import json
import logging
import math
import re
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Protocol

from pydantic import BaseModel, ConfigDict

from hotshop_agent.domain import IdentityKind
from hotshop_agent.embeddings.base import EmbeddingError, EmbeddingProvider
from hotshop_agent.knowledge import DocumentType, KnowledgeChunk, Visibility
from hotshop_agent.metrics import AgentMetrics


class RouteKind(StrEnum):
    STATIC = "STATIC"
    DYNAMIC_TOOL = "DYNAMIC_TOOL"
    FORBIDDEN = "FORBIDDEN"
    GENERAL = "GENERAL"


@dataclass(frozen=True)
class RouteDecision:
    kind: RouteKind
    document_types: tuple[DocumentType, ...] = ()
    tool_name: str | None = None
    tool_arguments: dict[str, Any] | None = None
    reason: str = ""


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    documentId: str
    title: str
    version: str
    source: str
    chunkId: str


@dataclass(frozen=True)
class SearchHit:
    chunk: KnowledgeChunk
    score: float


@dataclass(frozen=True)
class RetrievalResult:
    outcome: str
    hits: tuple[SearchHit, ...]
    citations: tuple[Citation, ...]
    elapsed_seconds: float


class VectorSearch(Protocol):
    async def search(
        self,
        vector: list[float],
        *,
        tenant_id: str,
        visibilities: tuple[Visibility, ...],
        document_types: tuple[DocumentType, ...],
        now: datetime,
        limit: int,
    ) -> list[SearchHit]: ...


class RagRetriever:
    def __init__(
        self,
        store: VectorSearch,
        embedding: EmbeddingProvider,
        metrics: AgentMetrics,
        *,
        tenant_id: str,
        top_k: int,
        minimum_score: float,
    ) -> None:
        self._store = store
        self._embedding = embedding
        self._metrics = metrics
        self._tenant_id = tenant_id
        self._top_k = top_k
        self._minimum_score = minimum_score

    async def retrieve(
        self,
        query: str,
        *,
        identity: IdentityKind,
        document_types: tuple[DocumentType, ...],
        now: datetime | None = None,
    ) -> RetrievalResult:
        started = time.perf_counter()
        outcome = "unavailable"
        hits: list[SearchHit] = []
        try:
            vector = await self._embedding.embed_query(query)
            self._metrics.embedding_requests.labels(self._embedding.name, "success").inc()
            hits = await self._store.search(
                vector,
                tenant_id=self._tenant_id,
                visibilities=allowed_visibilities(identity),
                document_types=document_types,
                now=now or datetime.now(UTC),
                limit=self._top_k,
            )
            hits = [hit for hit in hits if hit.score >= self._minimum_score]
            outcome = "hit" if hits else "empty"
        except EmbeddingError:
            self._metrics.embedding_requests.labels(self._embedding.name, "failure").inc()
        except Exception:
            logging.getLogger(__name__).warning(
                "rag retrieval unavailable",
                extra={
                    "event": "agent.rag.retrieval",
                    "outcome": "unavailable",
                    "errorType": "RetrievalError",
                    "parameterSummary": "content_omitted",
                },
            )
        elapsed = time.perf_counter() - started
        citations = tuple(
            Citation(
                documentId=hit.chunk.documentId,
                title=hit.chunk.title,
                version=hit.chunk.documentVersion,
                source=hit.chunk.source,
                chunkId=hit.chunk.chunkId,
            )
            for hit in hits
        )
        self._metrics.rag_requests.labels(outcome).inc()
        self._metrics.rag_latency.labels(outcome).observe(elapsed)
        return RetrievalResult(outcome, tuple(hits), citations, elapsed)


def route_query(query: str, identity: IdentityKind, owner_id: str) -> RouteDecision:
    normalized = query.casefold()
    cross_user = re.search(r"(?:用户|user)\s*#?(\d+)\s*(?:的)?\s*(?:订单|预约)", normalized)
    if cross_user and cross_user.group(1) != owner_id:
        return RouteDecision(RouteKind.FORBIDDEN, reason="cross_user_resource")

    if any(
        word in normalized
        for word in (
            "订单状态",
            "我的订单",
            "订单到哪",
            "订单进度",
            "订单处理到",
            "order status",
            "my order",
            "where is my order",
            "order progress",
        )
    ):
        return _user_tool(identity, "list_my_orders", {"limit": 10})
    if any(
        word in normalized
        for word in (
            "预约状态",
            "我的预约",
            "预约进度",
            "预约处理",
            "reservation status",
            "my reservation",
            "reservation progress",
        )
    ):
        return _user_tool(identity, "list_my_reservations", {"limit": 10})
    if any(
        word in normalized
        for word in (
            "价格",
            "售价",
            "多少钱",
            "库存",
            "还剩",
            "剩几",
            "卖完",
            "售罄",
            "缺货",
            "有货",
            "可售",
            "price",
            "cost",
            "inventory",
            "stock",
            "how many left",
            "sold out",
            "in stock",
            "available now",
        )
    ):
        product_id = _product_id(query)
        if product_id:
            return _user_tool(identity, "get_product", {"productId": product_id})
        return _user_tool(identity, "search_products", {"keyword": query[:200], "limit": 10})

    if any(
        phrase in normalized
        for phrase in ("到哪一步", "处理进度", "现在什么情况", "current status")
    ):
        return RouteDecision(RouteKind.FORBIDDEN, reason="ambiguous_dynamic_fact")

    if any(word in normalized for word in ("售后", "退换", "保修", "after-sales", "return policy")):
        return RouteDecision(
            RouteKind.STATIC,
            document_types=(DocumentType.AFTER_SALES_POLICY,),
        )
    if any(word in normalized for word in ("活动规则", "秒杀规则", "优惠规则", "campaign rule")):
        return RouteDecision(RouteKind.STATIC, document_types=(DocumentType.CAMPAIGN_RULE,))
    if any(word in normalized for word in ("faq", "常见问题", "怎么", "如何", "为什么")):
        return RouteDecision(RouteKind.STATIC, document_types=(DocumentType.FAQ,))
    return RouteDecision(RouteKind.GENERAL)


def serialize_evidence(result: RetrievalResult, *, max_chars: int) -> str:
    evidence: list[dict[str, Any]] = []
    for hit in result.hits:
        item = {
            "documentId": hit.chunk.documentId,
            "chunkId": hit.chunk.chunkId,
            "version": hit.chunk.documentVersion,
            "title": hit.chunk.title,
            "source": hit.chunk.source,
            "documentType": hit.chunk.documentType,
            "content": hit.chunk.content,
        }
        evidence.append(item)
        encoded = json.dumps(evidence, ensure_ascii=False, separators=(",", ":"))
        while len(encoded) > max_chars and item["content"]:
            excess = max(1, len(encoded) - max_chars)
            item["content"] = item["content"][:-excess]
            encoded = json.dumps(evidence, ensure_ascii=False, separators=(",", ":"))
        if len(encoded) > max_chars:
            evidence.pop()
        if len(encoded) >= max_chars or not item["content"]:
            break
    return json.dumps(evidence, ensure_ascii=False, separators=(",", ":"))


def allowed_visibilities(identity: IdentityKind) -> tuple[Visibility, ...]:
    if identity is IdentityKind.ADMINISTRATOR:
        return (Visibility.PUBLIC, Visibility.ADMIN)
    return (Visibility.PUBLIC, Visibility.USER)


class InMemoryVectorSearch:
    def __init__(self, entries: list[tuple[KnowledgeChunk, list[float]]]) -> None:
        self.entries = entries

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
        candidates = [
            SearchHit(chunk, _cosine(vector, embedded))
            for chunk, embedded in self.entries
            if chunk.tenantId == tenant_id
            and chunk.visibility in visibilities
            and chunk.documentType in document_types
            and chunk.effectiveFromEpoch <= epoch <= chunk.effectiveUntilEpoch
        ]
        return sorted(candidates, key=lambda hit: (-hit.score, hit.chunk.point_id))[:limit]


def _cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right, strict=True)) / max(
        math.sqrt(sum(a * a for a in left)) * math.sqrt(sum(b * b for b in right)), 1e-12
    )


def _user_tool(identity: IdentityKind, name: str, arguments: dict[str, Any]) -> RouteDecision:
    if identity is not IdentityKind.USER:
        return RouteDecision(RouteKind.FORBIDDEN, reason="identity_boundary")
    return RouteDecision(
        RouteKind.DYNAMIC_TOOL,
        tool_name=name,
        tool_arguments=arguments,
        reason="live_transaction_fact",
    )


def _product_id(query: str) -> str | None:
    match = re.search(r"(?:商品|product)\s*#?\s*([1-9][0-9]{0,18})", query, re.IGNORECASE)
    return match.group(1) if match else None
