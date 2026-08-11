from __future__ import annotations

import hashlib
import json
import re
import uuid
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class DocumentType(StrEnum):
    FAQ = "FAQ"
    AFTER_SALES_POLICY = "AFTER_SALES_POLICY"
    CAMPAIGN_RULE = "CAMPAIGN_RULE"


class Visibility(StrEnum):
    PUBLIC = "PUBLIC"
    USER = "USER"
    ADMIN = "ADMIN"


class KnowledgeDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tenantId: str = Field(pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")
    documentId: str = Field(pattern=r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
    documentVersion: str = Field(pattern=r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
    documentType: DocumentType
    visibility: Visibility
    title: str = Field(min_length=1, max_length=200)
    source: str = Field(min_length=1, max_length=500)
    locale: str = Field(pattern=r"^[a-z]{2}(?:-[A-Z]{2})?$")
    effectiveFrom: datetime | None = None
    effectiveUntil: datetime | None = None
    content: str = Field(min_length=1, max_length=100_000, repr=False)

    @field_validator("effectiveFrom", "effectiveUntil")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.tzinfo is None:
            raise ValueError("effective dates must include a timezone")
        return value

    @model_validator(mode="after")
    def validate_window_and_content(self) -> KnowledgeDocument:
        if self.effectiveFrom and self.effectiveUntil and self.effectiveFrom >= self.effectiveUntil:
            raise ValueError("effectiveFrom must precede effectiveUntil")
        normalized = self.content.casefold()
        forbidden = (
            "当前库存为",
            "实时库存为",
            "订单状态为",
            "预约状态为",
            "支付状态为",
            "current inventory is",
            "order status is",
        )
        if any(marker in normalized for marker in forbidden):
            raise ValueError("dynamic transaction facts are forbidden in static knowledge")
        if re.search(r"(?:现价|当前价格|售价)\s*[:：]?\s*[¥￥$]?\d", self.content):
            raise ValueError("product prices are forbidden in static knowledge")
        return self


class KnowledgeChunk(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tenantId: str
    documentId: str
    chunkId: str
    documentVersion: str
    documentType: DocumentType
    visibility: Visibility
    title: str
    source: str
    locale: str
    contentHash: str = Field(pattern=r"^[0-9a-f]{64}$")
    effectiveFrom: datetime | None
    effectiveUntil: datetime | None
    effectiveFromEpoch: int
    effectiveUntilEpoch: int
    content: str = Field(repr=False)

    @property
    def point_id(self) -> str:
        stable = "\x1f".join(
            (self.tenantId, self.documentId, self.documentVersion, self.chunkId, self.contentHash)
        )
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"hotshop-knowledge:{stable}"))


def load_documents(directory: Path, *, trusted_tenant: str) -> list[KnowledgeDocument]:
    documents: list[KnowledgeDocument] = []
    for path in sorted(directory.glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        document = KnowledgeDocument.model_validate(raw)
        if document.tenantId != trusted_tenant:
            raise ValueError(f"knowledge tenant mismatch in {path.name}")
        documents.append(document)
    if not documents:
        raise ValueError("knowledge source directory is empty")
    document_ids = [document.documentId for document in documents]
    if len(document_ids) != len(set(document_ids)):
        raise ValueError("each knowledge source must contain one current version per documentId")
    return documents


def chunk_documents(
    documents: list[KnowledgeDocument],
    *,
    chunk_size: int,
    overlap: int,
) -> list[KnowledgeChunk]:
    if not 200 <= chunk_size <= 2_000:
        raise ValueError("chunk_size must be between 200 and 2000")
    if not 0 <= overlap <= 200 or overlap >= chunk_size:
        raise ValueError("chunk overlap is invalid")
    chunks: list[KnowledgeChunk] = []
    for document in documents:
        content_hash = hashlib.sha256(document.content.encode("utf-8")).hexdigest()
        start = 0
        ordinal = 0
        while start < len(document.content):
            maximum = min(start + chunk_size, len(document.content))
            end = maximum
            if maximum < len(document.content):
                boundary = max(
                    document.content.rfind("\n", start, maximum),
                    document.content.rfind("。", start, maximum),
                )
                if boundary >= start + chunk_size // 2:
                    end = boundary + 1
            body = document.content[start:end].strip()
            if body:
                chunks.append(
                    KnowledgeChunk(
                        tenantId=document.tenantId,
                        documentId=document.documentId,
                        chunkId=f"{document.documentId}-{ordinal:04d}",
                        documentVersion=document.documentVersion,
                        documentType=document.documentType,
                        visibility=document.visibility,
                        title=document.title,
                        source=document.source,
                        locale=document.locale,
                        contentHash=content_hash,
                        effectiveFrom=document.effectiveFrom,
                        effectiveUntil=document.effectiveUntil,
                        effectiveFromEpoch=_epoch(document.effectiveFrom, 0),
                        effectiveUntilEpoch=_epoch(document.effectiveUntil, 253402300799),
                        content=body,
                    )
                )
                ordinal += 1
            if end >= len(document.content):
                break
            start = end - overlap
    return chunks


def knowledge_version(chunks: list[KnowledgeChunk]) -> str:
    digest = hashlib.sha256()
    for chunk in sorted(chunks, key=lambda item: item.point_id):
        digest.update(chunk.model_dump_json().encode("utf-8"))
    return digest.hexdigest()


def _epoch(value: datetime | None, default: int) -> int:
    return int(value.astimezone(UTC).timestamp()) if value else default
