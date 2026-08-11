from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

import httpx

from hotshop_agent.config import Settings
from hotshop_agent.embeddings import BailianEmbedding, DeterministicEmbedding, EmbeddingProvider
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.qdrant import KnowledgeIndexer, QdrantStore


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate and atomically rebuild HotShop knowledge"
    )
    parser.add_argument("command", choices=("validate", "rebuild", "status"))
    parser.add_argument("--directory", type=Path)
    return parser


async def _run(command: str, directory: Path | None) -> dict[str, Any]:
    settings = Settings()
    source = directory or settings.knowledge_directory
    async with httpx.AsyncClient() as client:
        embedding: EmbeddingProvider
        if settings.embedding_provider == "bailian":
            assert settings.bailian_embedding_api_key is not None
            embedding = BailianEmbedding(
                client,
                base_url=settings.bailian_embedding_base_url,
                api_key=settings.bailian_embedding_api_key.get_secret_value(),
                model=settings.bailian_embedding_model,
                dimension=settings.embedding_dimension,
                timeout_seconds=settings.embedding_timeout_seconds,
                max_items=settings.embedding_max_batch_size,
                max_chars=settings.embedding_max_input_chars,
            )
        else:
            embedding = DeterministicEmbedding(
                settings.embedding_dimension,
                max_items=settings.embedding_max_batch_size,
                max_chars=settings.embedding_max_input_chars,
            )
        store = QdrantStore(
            client,
            base_url=settings.qdrant_url,
            alias=settings.qdrant_alias,
            collection_prefix=settings.qdrant_collection_prefix,
            timeout_seconds=settings.qdrant_timeout_seconds,
            max_retries=settings.qdrant_max_retries,
        )
        indexer = KnowledgeIndexer(
            store,
            embedding,
            AgentMetrics(),
            tenant_id=settings.knowledge_tenant_id,
            chunk_size=settings.knowledge_chunk_size,
            chunk_overlap=settings.knowledge_chunk_overlap,
        )
        if command == "validate":
            documents, chunks, version = indexer.validate(source)
            return {
                "schemaVersion": "1.0",
                "valid": True,
                "version": version[:16],
                "documents": len(documents),
                "chunks": len(chunks),
            }
        status = await (indexer.rebuild(source) if command == "rebuild" else store.status())
        return {
            "schemaVersion": "1.0",
            "alias": status.alias,
            "collection": status.collection,
            "version": status.version,
            "points": status.points,
        }


def main() -> None:
    args = _parser().parse_args()
    print(json.dumps(asyncio.run(_run(args.command, args.directory)), separators=(",", ":")))


if __name__ == "__main__":
    main()
