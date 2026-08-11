from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterable
from dataclasses import dataclass
from typing import Any

import pytest

from hotshop_agent.config import Settings
from hotshop_agent.container import build_container
from hotshop_agent.domain import IdentityKind
from hotshop_agent.events import REDACTION, StreamingSanitizer
from hotshop_agent.providers.base import ModelCapabilities, ModelChunk, ModelDelta, ModelUsage
from hotshop_agent.security import JwtVerifier


@dataclass(frozen=True)
class SensitiveSample:
    name: str
    text: str
    secret: str


SENSITIVE_SAMPLES = (
    SensitiveSample("bearer", "Bearer abcDEF0123._-+/=", "abcDEF0123._-+/="),
    SensitiveSample(
        "jwt",
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI0MiIsImV4cCI6OTk5OTk5OTk5OX0.signatureABCDEFG",
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI0MiIsImV4cCI6OTk5OTk5OTk5OX0.signatureABCDEFG",
    ),
    SensitiveSample("api-key", "api_key=paid-secret-value", "paid-secret-value"),
    SensitiveSample("token", "token:token-secret-value", "token-secret-value"),
    SensitiveSample("cookie", "cookie=session-secret-value", "session-secret-value"),
    SensitiveSample(
        "client-assertion",
        "client_assertion=assertion-secret-value",
        "assertion-secret-value",
    ),
    SensitiveSample(
        "pkcs8",
        "-----BEGIN PRIVATE KEY-----\nPKCS8-SECRET\n-----END PRIVATE KEY-----",
        "PKCS8-SECRET",
    ),
    SensitiveSample(
        "rsa",
        "-----BEGIN RSA PRIVATE KEY-----\nRSA-SECRET\n-----END RSA PRIVATE KEY-----",
        "RSA-SECRET",
    ),
    SensitiveSample(
        "ec",
        "-----BEGIN EC PRIVATE KEY-----\nEC-SECRET\n-----END EC PRIVATE KEY-----",
        "EC-SECRET",
    ),
    SensitiveSample(
        "openssh",
        "-----BEGIN OPENSSH PRIVATE KEY-----\nOPENSSH-SECRET\n-----END OPENSSH PRIVATE KEY-----",
        "OPENSSH-SECRET",
    ),
    SensitiveSample(
        "encrypted",
        "-----BEGIN ENCRYPTED PRIVATE KEY-----\nENCRYPTED-SECRET\n"
        "-----END ENCRYPTED PRIVATE KEY-----",
        "ENCRYPTED-SECRET",
    ),
    SensitiveSample(
        "thinking",
        "<thinking>hidden chain of thought</thinking>",
        "hidden chain of thought",
    ),
    SensitiveSample("analysis", "<analysis>hidden analysis</analysis>", "hidden analysis"),
    SensitiveSample("system", "<system>hidden system prompt</system>", "hidden system prompt"),
)


def split_in_two(text: str) -> Iterable[tuple[str, ...]]:
    for first in range(1, len(text)):
        yield text[:first], text[first:]


def split_in_three(text: str) -> Iterable[tuple[str, ...]]:
    for first in range(1, len(text) - 1):
        for second in range(first + 1, len(text)):
            yield text[:first], text[first:second], text[second:]


def sanitize_chunks(chunks: Iterable[str]) -> str:
    sanitizer = StreamingSanitizer()
    output: list[str] = []
    for chunk in chunks:
        output.extend(sanitizer.feed(chunk))
    output.extend(sanitizer.flush())
    return "".join(output)


@pytest.mark.parametrize("sample", SENSITIVE_SAMPLES, ids=lambda sample: sample.name)
def test_every_two_part_split_redacts_sensitive_sample(sample: SensitiveSample) -> None:
    for chunks in split_in_two(sample.text):
        output = sanitize_chunks(chunks)
        assert sample.secret not in output
        assert REDACTION in output


@pytest.mark.parametrize("sample", SENSITIVE_SAMPLES, ids=lambda sample: sample.name)
def test_every_three_part_split_redacts_sensitive_sample(sample: SensitiveSample) -> None:
    for chunks in split_in_three(sample.text):
        output = sanitize_chunks(chunks)
        assert sample.secret not in output
        assert REDACTION in output


@pytest.mark.parametrize("sample", SENSITIVE_SAMPLES, ids=lambda sample: sample.name)
def test_character_by_character_split_redacts_sensitive_sample(
    sample: SensitiveSample,
) -> None:
    output = sanitize_chunks(sample.text)
    assert sample.secret not in output
    assert REDACTION in output


@pytest.mark.parametrize(
    "text",
    (
        "普通中文跨块保持原样。",
        "Normal English text remains ordered.",
        "Unicode: 商品🔥 café naïve — 東京.",
    ),
)
def test_normal_text_streams_without_loss_or_reordering(text: str) -> None:
    sanitizer = StreamingSanitizer()
    streamed: list[str] = []
    for character in text:
        streamed.extend(sanitizer.feed(character))
    assert streamed
    streamed.extend(sanitizer.flush())
    assert "".join(streamed) == text


def test_flush_releases_benign_partial_candidate() -> None:
    sanitizer = StreamingSanitizer()
    streamed = list(sanitizer.feed("普通文本 token"))
    assert "token" not in "".join(streamed)
    streamed.extend(sanitizer.flush())
    assert "".join(streamed) == "普通文本 token"


def test_candidate_over_limit_fails_closed_with_bounded_carry() -> None:
    sanitizer = StreamingSanitizer(max_candidate_chars=64)
    streamed: list[str] = []
    for character in "Bearer " + "x" * 512:
        streamed.extend(sanitizer.feed(character))
        assert sanitizer.pending_chars <= 64
    streamed.extend(sanitizer.flush())
    output = "".join(streamed)
    assert output == REDACTION
    assert "x" not in output


@pytest.mark.parametrize(
    "text",
    (
        "token" + " " * 128 + "=" + " " * 128 + "secret-value trailing",
        "Bearer" + " " * 128 + "secret-value trailing",
    ),
)
def test_overlong_prefix_discards_later_value_without_unbounded_carry(text: str) -> None:
    sanitizer = StreamingSanitizer(max_candidate_chars=64)
    streamed: list[str] = []
    for character in text:
        streamed.extend(sanitizer.feed(character))
        assert sanitizer.pending_chars <= 64
    streamed.extend(sanitizer.flush())
    output = "".join(streamed)
    assert "secret-value" not in output
    assert REDACTION in output
    assert output.endswith(" trailing")


def test_discard_never_flushes_unresolved_sensitive_candidate() -> None:
    sanitizer = StreamingSanitizer()
    assert sanitizer.feed("Bearer unresolved-secret") == ()
    sanitizer.discard()
    assert sanitizer.flush() == ()


class ChunkModel:
    name = "chunk-test"
    model_name = "chunk-test"
    capabilities = ModelCapabilities("fake", "fake", True, True, "not_provided")

    def __init__(self, chunks: tuple[str, ...], *, wait_after_chunks: bool = False) -> None:
        self.chunks = chunks
        self.wait_after_chunks = wait_after_chunks
        self.active_calls = 0
        self.release = asyncio.Event()

    async def stream(self, _prompt: str) -> AsyncIterator[ModelChunk]:
        self.active_calls += 1
        try:
            for chunk in self.chunks:
                yield ModelDelta(chunk)
            if self.wait_after_chunks:
                await self.release.wait()
            yield ModelUsage(1, 1, 0)
        finally:
            self.active_calls -= 1


async def run_service_stream(
    settings: Settings,
    issue_token: Any,
    chunks: tuple[str, ...],
) -> str:
    provider = ChunkModel(chunks)
    container = build_container(settings, provider=provider)
    try:
        principal = JwtVerifier(settings).verify(
            issue_token(IdentityKind.USER),
            IdentityKind.USER,
        )
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "test")
        run = await container.service.start_run(session.id, message.id, principal)
        handle = await container.service.handle(run.id, principal)
        deltas: list[str] = []
        while True:
            event = await asyncio.wait_for(handle.queue.get(), timeout=1)
            handle.queue.task_done()
            if event.type == "message.delta":
                deltas.append(event.data["delta"])
            if event.type == "done":
                break
        await asyncio.wait_for(handle.task, timeout=1)
        return "".join(deltas)
    finally:
        await container.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("sample", SENSITIVE_SAMPLES, ids=lambda sample: sample.name)
async def test_real_agent_service_stream_redacts_character_chunks(
    settings: Settings,
    issue_token: Any,
    sample: SensitiveSample,
) -> None:
    output = await run_service_stream(settings, issue_token, tuple(sample.text))
    assert sample.secret not in output
    assert REDACTION in output


@pytest.mark.asyncio
async def test_real_agent_service_preserves_normal_unicode_chunks(
    settings: Settings,
    issue_token: Any,
) -> None:
    text = "中文 and English 🔥 café — 東京"
    output = await run_service_stream(settings, issue_token, tuple(text))
    assert output == text


@pytest.mark.asyncio
async def test_real_agent_service_cancel_discards_pending_candidate(
    settings: Settings,
    issue_token: Any,
) -> None:
    secret = "unresolved-sensitive-value"  # noqa: S105
    provider = ChunkModel((f"Bearer {secret}",), wait_after_chunks=True)
    container = build_container(settings, provider=provider)
    try:
        principal = JwtVerifier(settings).verify(
            issue_token(IdentityKind.USER),
            IdentityKind.USER,
        )
        session = await container.service.create_session(principal)
        message = await container.service.add_message(session.id, principal, "test")
        run = await container.service.start_run(session.id, message.id, principal)
        handle = await container.service.handle(run.id, principal)
        for _ in range(50):
            if provider.active_calls:
                break
            await asyncio.sleep(0.01)
        await asyncio.wait_for(container.service.cancel(run.id, principal), timeout=1)
        events = []
        while not handle.queue.empty():
            events.append(handle.queue.get_nowait())
        output = "".join(
            str(event.data["delta"]) for event in events if event.type == "message.delta"
        )
        assert secret not in output
        assert handle.task.done()
        assert provider.active_calls == 0
    finally:
        await container.close()
