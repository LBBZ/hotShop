from __future__ import annotations

import math
from typing import Protocol


class EmbeddingError(Exception):
    """A bounded error that never includes input text or provider responses."""


class EmbeddingProvider(Protocol):
    name: str
    dimension: int
    max_batch_size: int

    async def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


def validate_inputs(texts: list[str], *, max_items: int, max_chars: int) -> None:
    if not texts or len(texts) > max_items:
        raise EmbeddingError("embedding batch size is invalid")
    if any(not text or len(text) > max_chars for text in texts):
        raise EmbeddingError("embedding input length is invalid")


def validate_vectors(vectors: object, *, expected_count: int, dimension: int) -> list[list[float]]:
    if not isinstance(vectors, list) or len(vectors) != expected_count:
        raise EmbeddingError("embedding response count is invalid")
    checked: list[list[float]] = []
    for vector in vectors:
        if not isinstance(vector, list) or len(vector) != dimension:
            raise EmbeddingError("embedding response dimension is invalid")
        values: list[float] = []
        for item in vector:
            if isinstance(item, bool) or not isinstance(item, int | float):
                raise EmbeddingError("embedding response value is invalid")
            value = float(item)
            if not math.isfinite(value):
                raise EmbeddingError("embedding response value is invalid")
            values.append(value)
        checked.append(values)
    return checked
