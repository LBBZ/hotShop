from __future__ import annotations

import hashlib
import math
import re
import unicodedata

from hotshop_agent.embeddings.base import validate_inputs, validate_vectors

_ASCII_TOKEN = re.compile(r"[a-z0-9]+")


class DeterministicEmbedding:
    """Stable local feature hashing; never uses Python's randomized hash()."""

    name = "deterministic"

    def __init__(
        self,
        dimension: int = 128,
        *,
        max_items: int = 64,
        max_chars: int = 8_000,
    ) -> None:
        if not 16 <= dimension <= 4096:
            raise ValueError("embedding dimension must be between 16 and 4096")
        self.dimension = dimension
        self.max_batch_size = max_items
        self.max_chars = max_chars

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        validate_inputs(texts, max_items=self.max_batch_size, max_chars=self.max_chars)
        return validate_vectors(
            [self._embed(text) for text in texts],
            expected_count=len(texts),
            dimension=self.dimension,
        )

    async def embed_query(self, text: str) -> list[float]:
        return (await self.embed_documents([text]))[0]

    def _embed(self, text: str) -> list[float]:
        normalized = unicodedata.normalize("NFKC", text).casefold()
        compact = "".join(character for character in normalized if not character.isspace())
        features = list(_ASCII_TOKEN.findall(normalized))
        features.extend(compact[index : index + 2] for index in range(max(0, len(compact) - 1)))
        features.extend(character for character in compact if not character.isascii())
        if not features:
            features = [normalized]
        vector = [0.0] * self.dimension
        for feature in features:
            digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=16).digest()
            index = int.from_bytes(digest[:8], "big") % self.dimension
            sign = 1.0 if digest[8] & 1 else -1.0
            vector[index] += sign
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            vector[0] = 1.0
            return vector
        return [value / norm for value in vector]
