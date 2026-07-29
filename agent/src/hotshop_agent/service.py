from __future__ import annotations

import asyncio
import uuid
from contextlib import aclosing
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

from hotshop_agent.config import Settings
from hotshop_agent.domain import (
    AgentMessage,
    AgentRun,
    AgentSession,
    IdentityKind,
    MessageState,
    Principal,
    RunState,
    SessionState,
    utc_now,
)
from hotshop_agent.events import StreamEvent, StreamingSanitizer
from hotshop_agent.graph import ADMIN_POLICY, USER_POLICY
from hotshop_agent.metrics import AgentMetrics
from hotshop_agent.providers.base import (
    ModelDelta,
    ModelPermanentError,
    ModelTemporaryError,
    ModelUsage,
)
from hotshop_agent.reliability import (
    CircuitOpenError,
    ConcurrencyLimitError,
    ModelTimeoutError,
    ReliableModel,
)
from hotshop_agent.state import StateStore


class ResourceNotFoundError(Exception):
    pass


class AccessDeniedError(Exception):
    pass


class InvalidStateError(Exception):
    pass


@dataclass
class RunHandle:
    queue: asyncio.Queue[StreamEvent]
    task: asyncio.Task[None]
    done: asyncio.Event


EVENT_QUEUE_MAXSIZE = 128


class AgentService:
    def __init__(
        self,
        settings: Settings,
        store: StateStore,
        model: ReliableModel,
        metrics: AgentMetrics,
        graph: Any,
    ) -> None:
        self._settings = settings
        self.store = store
        self.model = model
        self.metrics = metrics
        self._graph = graph
        self._handles: dict[str, RunHandle] = {}
        self._handles_lock = asyncio.Lock()

    async def create_session(
        self,
        principal: Principal,
        *,
        scopes: frozenset[str] = frozenset(),
    ) -> AgentSession:
        now = utc_now()
        session = AgentSession(
            id=str(uuid.uuid4()),
            owner_id=principal.subject_user_id,
            identity_kind=principal.kind,
            state=SessionState.ACTIVE,
            scopes=scopes,
            created_at=now,
            expires_at=now + timedelta(seconds=self._settings.session_ttl_seconds),
        )
        await self.store.put_session(session)
        return session

    async def add_message(
        self,
        session_id: str,
        principal: Principal,
        content: str,
    ) -> AgentMessage:
        session = await self._owned_session(session_id, principal)
        if session.state is not SessionState.ACTIVE:
            raise InvalidStateError
        message = AgentMessage(
            id=str(uuid.uuid4()),
            session_id=session_id,
            content=content,
            state=MessageState.CREATED,
            created_at=utc_now(),
        )
        await self.store.put_message(message)
        return message

    async def start_run(
        self,
        session_id: str,
        message_id: str,
        principal: Principal,
    ) -> AgentRun:
        session = await self._owned_session(session_id, principal)
        message = await self.store.get_message(message_id)
        if message is None or message.session_id != session.id:
            raise ResourceNotFoundError
        now = utc_now()
        run = AgentRun(
            id=str(uuid.uuid4()),
            session_id=session.id,
            message_id=message.id,
            owner_id=principal.subject_user_id,
            state=RunState.QUEUED,
            created_at=now,
            updated_at=now,
        )
        await self.store.put_run(run)
        queue: asyncio.Queue[StreamEvent] = asyncio.Queue(maxsize=EVENT_QUEUE_MAXSIZE)
        done = asyncio.Event()
        task = asyncio.create_task(
            self._execute(run, session, message, queue, done),
            name=f"agent-run-{run.id}",
        )
        async with self._handles_lock:
            self._handles[run.id] = RunHandle(queue=queue, task=task, done=done)
        return run

    async def handle(self, run_id: str, principal: Principal) -> RunHandle:
        run = await self.store.get_run(run_id)
        if run is None:
            raise ResourceNotFoundError
        await self._owned_session(run.session_id, principal)
        async with self._handles_lock:
            handle = self._handles.get(run_id)
        if handle is None:
            raise ResourceNotFoundError
        return handle

    async def cancel(self, run_id: str, principal: Principal) -> AgentRun:
        handle = await self.handle(run_id, principal)
        if not handle.task.done():
            handle.task.cancel()
        await asyncio.gather(handle.task, return_exceptions=True)
        run = await self.store.get_run(run_id)
        if run is None:
            raise ResourceNotFoundError
        return run

    async def release_handle(self, run_id: str) -> None:
        async with self._handles_lock:
            handle = self._handles.get(run_id)
            if handle is not None and handle.done.is_set():
                del self._handles[run_id]

    async def shutdown(self) -> None:
        async with self._handles_lock:
            tasks = [handle.task for handle in self._handles.values() if not handle.task.done()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _execute(
        self,
        run: AgentRun,
        session: AgentSession,
        message: AgentMessage,
        queue: asyncio.Queue[StreamEvent],
        done: asyncio.Event,
    ) -> None:
        sequence = 0
        sanitizer = StreamingSanitizer()

        def build_event(event_type: str, data: dict[str, Any]) -> StreamEvent:
            nonlocal sequence
            sequence += 1
            return StreamEvent.model_validate(
                {
                    "type": event_type,
                    "sessionId": session.id,
                    "runId": run.id,
                    "messageId": message.id,
                    "sequence": sequence,
                    "data": data,
                }
            )

        async def emit(event_type: str, data: dict[str, Any]) -> None:
            event = build_event(event_type, data)
            await queue.put(event)

        def emit_terminal(events: tuple[tuple[str, dict[str, Any]], ...]) -> None:
            terminal_events = [build_event(event_type, data) for event_type, data in events]
            retained: list[StreamEvent] = []
            while not queue.empty():
                retained.append(queue.get_nowait())
                queue.task_done()
            while len(retained) + len(terminal_events) > queue.maxsize:
                delta_at = next(
                    (
                        index
                        for index, event in enumerate(retained)
                        if event.type == "message.delta"
                    ),
                    0,
                )
                retained.pop(delta_at)
            for event in (*retained, *terminal_events):
                queue.put_nowait(event)

        self.metrics.active_runs.inc()
        try:
            run.state = RunState.RUNNING
            run.updated_at = utc_now()
            await self.store.put_run(run)
            await emit(
                "session.created",
                {"state": session.state, "scopes": sorted(session.scopes)},
            )
            await emit("message.started", {"state": run.state})
            policy = USER_POLICY if session.identity_kind is IdentityKind.USER else ADMIN_POLICY
            graph_state = await self._graph.ainvoke(
                {"prompt": message.content, "policy": policy, "model_input": ""}
            )
            async with aclosing(
                self.model.stream(run.owner_id, graph_state["model_input"])
            ) as model_stream:
                async for chunk in model_stream:
                    if isinstance(chunk, ModelDelta):
                        for safe_delta in sanitizer.feed(chunk.text):
                            await emit("message.delta", {"delta": safe_delta})
                    elif isinstance(chunk, ModelUsage):
                        run.input_tokens = chunk.input_tokens
                        run.output_tokens = chunk.output_tokens
                        run.estimated_cost_usd = chunk.estimated_cost_usd
                        provider = self.model.provider.name
                        self.metrics.model_tokens.labels(provider, "input").inc(chunk.input_tokens)
                        self.metrics.model_tokens.labels(provider, "output").inc(
                            chunk.output_tokens
                        )
                        self.metrics.model_cost.labels(provider).inc(chunk.estimated_cost_usd)
                        await emit(
                            "usage",
                            {
                                "inputTokens": chunk.input_tokens,
                                "outputTokens": chunk.output_tokens,
                                "estimatedCostUsd": chunk.estimated_cost_usd,
                            },
                        )
            for safe_delta in sanitizer.flush():
                await emit("message.delta", {"delta": safe_delta})
            run.state = RunState.COMPLETED
            message.state = MessageState.COMPLETED
            await self.store.put_message(message)
            emit_terminal(
                (
                    ("message.completed", {"state": run.state}),
                    ("done", {"state": run.state}),
                )
            )
        except asyncio.CancelledError:
            sanitizer.discard()
            run.state = RunState.CANCELLED
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "RUN_CANCELLED",
                            "message": "Run was cancelled",
                            "retryable": False,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        except ModelTimeoutError:
            sanitizer.discard()
            run.state = RunState.TIMED_OUT
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "MODEL_TIMEOUT",
                            "message": "Model timed out",
                            "retryable": True,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        except ConcurrencyLimitError:
            sanitizer.discard()
            run.state = RunState.FAILED
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "CONCURRENCY_LIMITED",
                            "message": "Agent is busy",
                            "retryable": True,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        except CircuitOpenError:
            sanitizer.discard()
            run.state = RunState.FAILED
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "MODEL_UNAVAILABLE",
                            "message": "Model is unavailable",
                            "retryable": True,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        except (ModelTemporaryError, ModelPermanentError):
            sanitizer.discard()
            run.state = RunState.FAILED
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "MODEL_ERROR",
                            "message": "Model request failed",
                            "retryable": True,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        except Exception:
            sanitizer.discard()
            run.state = RunState.FAILED
            emit_terminal(
                (
                    (
                        "error",
                        {
                            "code": "INTERNAL_ERROR",
                            "message": "Run failed",
                            "retryable": False,
                        },
                    ),
                    ("done", {"state": run.state}),
                )
            )
        finally:
            run.updated_at = utc_now()
            try:
                await self.store.put_run(run)
            finally:
                try:
                    self.metrics.run_outcomes.labels(run.state).inc()
                    self.metrics.active_runs.dec()
                finally:
                    done.set()
                    loop = asyncio.get_running_loop()
                    loop.call_later(
                        self._settings.run_ttl_seconds,
                        lambda: loop.create_task(self.release_handle(run.id)),
                    )

    async def _owned_session(
        self,
        session_id: str,
        principal: Principal,
    ) -> AgentSession:
        session = await self.store.get_session(session_id)
        if session is None:
            raise ResourceNotFoundError
        if (
            session.owner_id != principal.subject_user_id
            or session.identity_kind is not principal.kind
        ):
            raise AccessDeniedError
        return session
