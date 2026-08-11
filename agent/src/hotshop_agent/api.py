from __future__ import annotations

import asyncio
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Annotated, cast

from fastapi import APIRouter, Depends, FastAPI, Header, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, ConfigDict, Field, field_validator

from hotshop_agent.config import Settings
from hotshop_agent.container import Container, build_container
from hotshop_agent.domain import AgentMessage, AgentRun, AgentSession, Credential, IdentityKind
from hotshop_agent.exchange import TokenExchangeError
from hotshop_agent.observability import (
    configure_logging,
    parse_remote_parent,
    request_id,
    reset_request_id,
    set_request_id,
)
from hotshop_agent.security import (
    ALLOWED_DELEGATION_SCOPES,
    AuthenticationError,
    bearer_credential,
)
from hotshop_agent.service import (
    AccessDeniedError,
    InvalidStateError,
    ResourceNotFoundError,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class UserSessionRequest(StrictModel):
    scopes: frozenset[str] = ALLOWED_DELEGATION_SCOPES

    @field_validator("scopes")
    @classmethod
    def validate_scopes(cls, value: frozenset[str]) -> frozenset[str]:
        if not value or not ALLOWED_DELEGATION_SCOPES.issuperset(value):
            raise ValueError("scopes contain a capability not allowed by TASK-15")
        return value


class AdministratorSessionRequest(StrictModel):
    pass


class MessageRequest(StrictModel):
    content: str = Field(min_length=1, max_length=16_000)


class RunRequest(StrictModel):
    messageId: uuid.UUID


class SessionResponse(StrictModel):
    id: str
    state: str
    scopes: list[str]
    created_at: datetime = Field(alias="createdAt")
    expires_at: datetime = Field(alias="expiresAt")

    @classmethod
    def from_domain(cls, session: AgentSession) -> SessionResponse:
        return cls(
            id=session.id,
            state=session.state,
            scopes=sorted(session.scopes),
            createdAt=session.created_at,
            expiresAt=session.expires_at,
        )


class MessageResponse(StrictModel):
    id: str
    session_id: str = Field(alias="sessionId")
    state: str
    created_at: datetime = Field(alias="createdAt")

    @classmethod
    def from_domain(cls, message: AgentMessage) -> MessageResponse:
        return cls(
            id=message.id,
            sessionId=message.session_id,
            state=message.state,
            createdAt=message.created_at,
        )


class RunResponse(StrictModel):
    id: str
    session_id: str = Field(alias="sessionId")
    message_id: str = Field(alias="messageId")
    state: str
    created_at: datetime = Field(alias="createdAt")

    @classmethod
    def from_domain(cls, run: AgentRun) -> RunResponse:
        return cls(
            id=run.id,
            sessionId=run.session_id,
            messageId=run.message_id,
            state=run.state,
            createdAt=run.created_at,
        )


def get_container(request: Request) -> Container:
    return cast(Container, request.app.state.container)


def user_credential(
    authorization: Annotated[str | None, Header()] = None,
    container: Annotated[Container, Depends(get_container)] = None,  # type: ignore[assignment]
) -> Credential:
    return bearer_credential(authorization, container.verifier, IdentityKind.USER)


def administrator_credential(
    authorization: Annotated[str | None, Header()] = None,
    container: Annotated[Container, Depends(get_container)] = None,  # type: ignore[assignment]
) -> Credential:
    return bearer_credential(authorization, container.verifier, IdentityKind.ADMINISTRATOR)


ContainerDependency = Annotated[Container, Depends(get_container)]
UserCredential = Annotated[Credential, Depends(user_credential)]
AdministratorCredential = Annotated[Credential, Depends(administrator_credential)]


def build_user_router() -> APIRouter:
    router = APIRouter(prefix="/api/v1/agent", tags=["User Agent"])

    @router.post("/sessions", response_model=SessionResponse, status_code=status.HTTP_201_CREATED)
    async def create_session(
        body: UserSessionRequest,
        credential: UserCredential,
        container: ContainerDependency,
    ) -> SessionResponse:
        delegation = await container.exchange.exchange(credential.token, body.scopes)
        if delegation.principal.subject_user_id != credential.principal.subject_user_id:
            raise AuthenticationError
        session = await container.service.create_session(
            credential.principal,
            scopes=delegation.principal.scopes,
        )
        return SessionResponse.from_domain(session)

    add_conversation_routes(router, IdentityKind.USER)
    return router


def build_administrator_router() -> APIRouter:
    router = APIRouter(prefix="/admin/api/v1/agent", tags=["Administrator Agent"])

    @router.post("/sessions", response_model=SessionResponse, status_code=status.HTTP_201_CREATED)
    async def create_session(
        body: AdministratorSessionRequest,
        credential: AdministratorCredential,
        container: ContainerDependency,
    ) -> SessionResponse:
        del body
        session = await container.service.create_session(credential.principal)
        return SessionResponse.from_domain(session)

    add_conversation_routes(router, IdentityKind.ADMINISTRATOR)
    return router


def add_conversation_routes(router: APIRouter, kind: IdentityKind) -> None:
    @router.post(
        "/sessions/{session_id}/messages",
        response_model=MessageResponse,
        status_code=status.HTTP_201_CREATED,
    )
    async def add_message(
        session_id: uuid.UUID,
        body: MessageRequest,
        container: ContainerDependency,
        authorization: Annotated[str | None, Header()] = None,
    ) -> MessageResponse:
        credential = bearer_credential(authorization, container.verifier, kind)
        message = await container.service.add_message(
            str(session_id),
            credential.principal,
            body.content,
        )
        return MessageResponse.from_domain(message)

    @router.post(
        "/sessions/{session_id}/runs",
        response_model=RunResponse,
        status_code=status.HTTP_202_ACCEPTED,
    )
    async def start_run(
        session_id: uuid.UUID,
        body: RunRequest,
        container: ContainerDependency,
        authorization: Annotated[str | None, Header()] = None,
    ) -> RunResponse:
        credential = bearer_credential(authorization, container.verifier, kind)
        tool_credential = credential
        if kind is IdentityKind.USER:
            session = await container.service.get_session(str(session_id), credential.principal)
            tool_credential = await container.exchange.exchange(credential.token, session.scopes)
            if tool_credential.principal.subject_user_id != credential.principal.subject_user_id:
                raise AuthenticationError
        run = await container.service.start_run(
            str(session_id),
            str(body.messageId),
            credential.principal,
            tool_credential=tool_credential,
        )
        return RunResponse.from_domain(run)

    @router.get("/runs/{run_id}/events")
    async def stream_events(
        run_id: uuid.UUID,
        request: Request,
        container: ContainerDependency,
        authorization: Annotated[str | None, Header()] = None,
    ) -> StreamingResponse:
        credential = bearer_credential(authorization, container.verifier, kind)
        handle = await container.service.handle(str(run_id), credential.principal)

        async def event_source() -> AsyncIterator[str]:
            disconnected = False
            try:
                while True:
                    if await request.is_disconnected():
                        disconnected = True
                        break
                    try:
                        event = await asyncio.wait_for(handle.queue.get(), timeout=0.1)
                    except TimeoutError:
                        if handle.done.is_set() and handle.queue.empty():
                            break
                        continue
                    handle.queue.task_done()
                    yield event.encode()
                    if event.type == "done":
                        break
            except asyncio.CancelledError:
                disconnected = True
                raise
            finally:
                if (disconnected or not handle.done.is_set()) and not handle.task.done():
                    await container.service.cancel(str(run_id), credential.principal)
                await container.service.release_handle(str(run_id))

        return StreamingResponse(
            event_source(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "Pragma": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )

    @router.delete("/runs/{run_id}", response_model=RunResponse)
    async def cancel_run(
        run_id: uuid.UUID,
        container: ContainerDependency,
        authorization: Annotated[str | None, Header()] = None,
    ) -> RunResponse:
        credential = bearer_credential(authorization, container.verifier, kind)
        run = await container.service.cancel(str(run_id), credential.principal)
        return RunResponse.from_domain(run)


def problem(status_code: int, code: str, title: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        media_type="application/problem+json",
        content={
            "type": f"https://hotshop.local/problems/{code.lower().replace('_', '-')}",
            "title": title,
            "status": status_code,
            "detail": detail,
            "code": code,
        },
    )


def register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AuthenticationError)
    async def authentication_error(_request: Request, _exc: AuthenticationError) -> JSONResponse:
        return problem(
            401,
            "AUTHENTICATION_REQUIRED",
            "Authentication required",
            "Invalid credential",
        )

    @app.exception_handler(TokenExchangeError)
    async def exchange_error(_request: Request, _exc: TokenExchangeError) -> JSONResponse:
        return problem(
            503,
            "AUTHENTICATION_SERVICE_UNAVAILABLE",
            "Authentication unavailable",
            "Could not establish Agent delegation",
        )

    @app.exception_handler(ResourceNotFoundError)
    async def not_found_error(_request: Request, _exc: ResourceNotFoundError) -> JSONResponse:
        return problem(404, "RESOURCE_NOT_FOUND", "Resource not found", "Resource was not found")

    @app.exception_handler(AccessDeniedError)
    async def access_error(_request: Request, _exc: AccessDeniedError) -> JSONResponse:
        return problem(403, "ACCESS_DENIED", "Access denied", "Access is denied")

    @app.exception_handler(InvalidStateError)
    async def state_error(_request: Request, _exc: InvalidStateError) -> JSONResponse:
        return problem(409, "INVALID_STATE", "Invalid state", "Operation is not allowed")

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _exc: RequestValidationError) -> JSONResponse:
        return problem(400, "VALIDATION_FAILED", "Validation failed", "Request is invalid")


def create_app(
    settings: Settings | None = None,
    *,
    container: Container | None = None,
) -> FastAPI:
    actual_settings = settings or Settings()
    actual_container = container or build_container(actual_settings)
    configure_logging(actual_settings)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        try:
            yield
        finally:
            await actual_container.close()

    app = FastAPI(
        title="HotShop Agent",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    app.state.container = actual_container

    @app.middleware("http")
    async def observation_middleware(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        correlation_id = request_id(request.headers.get("X-Request-ID"))
        token = set_request_id(correlation_id)
        parent = parse_remote_parent(request.headers.get("traceparent"))
        try:
            async with actual_container.telemetry.span(
                f"{request.method} {request.url.path}",
                kind="SERVER",
                remote_parent=parent,
                tags={"http.method": request.method, "http.route": request.url.path},
            ) as active:
                response = await call_next(request)
                response.headers["X-Request-ID"] = correlation_id
                response.headers["X-Trace-ID"] = active.trace_id
                return response
        finally:
            reset_request_id(token)

    register_error_handlers(app)
    app.include_router(build_user_router())
    app.include_router(build_administrator_router())

    @app.get("/health/live", include_in_schema=False)
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/health/ready", include_in_schema=False)
    async def ready(response: Response) -> dict[str, str]:
        if not await actual_container.store.ready():
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
            return {"status": "NOT_READY"}
        return {"status": "READY", "provider": actual_settings.provider}

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(
            content=generate_latest(actual_container.service.metrics.registry),
            media_type=CONTENT_TYPE_LATEST,
        )

    return app
