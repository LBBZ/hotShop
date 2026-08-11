from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AGENT_",
        env_file=None,
        case_sensitive=False,
        extra="ignore",
    )

    environment: Literal["local", "test", "production"] = "local"
    provider: Literal["fake", "qwen"] = "fake"
    state_backend: Literal["memory", "redis"] = "memory"
    redis_url: SecretStr = SecretStr("redis://localhost:7379/0")
    redis_key_prefix: str = "hotshop:agent:"
    session_ttl_seconds: int = Field(default=3600, ge=60, le=86400)
    run_ttl_seconds: int = Field(default=900, ge=60, le=86400)

    portal_base_url: str = "http://localhost:8080"
    administrator_base_url: str = "http://localhost:8088"
    tool_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    token_exchange_path: str = "/agent/api/v1/auth/token-exchange"  # noqa: S105
    token_exchange_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    assertion_issuer: str = "https://agent.hotshop.local/service"
    assertion_audience: str = "hotshop-agent-token-exchange"
    assertion_type: str = "client-auth+jwt"
    assertion_client_id: str = "hotshop-agent-service"
    assertion_kid: str = "agent-service-local-1"
    assertion_private_key_path: Path | None = None
    assertion_ttl_seconds: int = Field(default=60, ge=10, le=60)

    user_issuer: str = "https://auth.hotshop.local/user"
    user_audience: str = "hotshop-portal-api"
    user_type: str = "user-access+jwt"
    user_token_use: str = "user_access"  # noqa: S105
    user_public_keys: dict[str, Path] = Field(default_factory=dict)
    user_max_ttl_seconds: int = Field(default=900, ge=60, le=900)

    administrator_issuer: str = "https://auth.hotshop.local/administrator"
    administrator_audience: str = "hotshop-admin-api"
    administrator_type: str = "administrator-access+jwt"
    administrator_token_use: str = "administrator_access"  # noqa: S105
    administrator_public_keys: dict[str, Path] = Field(default_factory=dict)
    administrator_max_ttl_seconds: int = Field(default=900, ge=60, le=900)

    delegation_issuer: str = "https://auth.hotshop.local/agent-delegation"
    delegation_audience: str = "hotshop-agent-api"
    delegation_type: str = "agent-delegation+jwt"
    delegation_token_use: str = "agent_delegation"  # noqa: S105
    delegation_public_keys: dict[str, Path] = Field(default_factory=dict)
    delegation_max_ttl_seconds: int = Field(default=300, ge=60, le=300)
    jwt_clock_skew_seconds: int = Field(default=30, ge=0, le=60)

    qwen_api_key: SecretStr | None = None
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"
    qwen_input_cost_per_million_usd: float = Field(default=0.0, ge=0)
    qwen_output_cost_per_million_usd: float = Field(default=0.0, ge=0)
    model_timeout_seconds: float = Field(default=15.0, gt=0, le=120)
    model_max_retries: int = Field(default=2, ge=0, le=5)
    model_retry_base_seconds: float = Field(default=0.05, ge=0, le=5)
    circuit_failure_threshold: int = Field(default=3, ge=1, le=20)
    circuit_recovery_seconds: float = Field(default=30.0, gt=0, le=600)
    global_concurrency_limit: int = Field(default=16, ge=1, le=1000)
    user_concurrency_limit: int = Field(default=2, ge=1, le=100)
    zipkin_endpoint: str = "http://localhost:9411/api/v2/spans"
    trace_sample_ratio: float = Field(default=1.0, ge=0.0, le=1.0)

    @field_validator(
        "portal_base_url",
        "administrator_base_url",
        "qwen_base_url",
        "zipkin_endpoint",
    )
    @classmethod
    def validate_fixed_http_url(cls, value: str) -> str:
        if not (value.startswith("http://") or value.startswith("https://")):
            raise ValueError("configured base URL must use http or https")
        return value.rstrip("/")

    @field_validator("redis_key_prefix")
    @classmethod
    def validate_key_prefix(cls, value: str) -> str:
        if value != "hotshop:agent:":
            raise ValueError("Redis keys must use the hotshop:agent: namespace")
        return value

    @model_validator(mode="after")
    def validate_provider(self) -> Settings:
        if self.provider == "qwen" and (
            self.qwen_api_key is None or not self.qwen_api_key.get_secret_value().strip()
        ):
            raise ValueError("AGENT_QWEN_API_KEY is required only when AGENT_PROVIDER=qwen")
        if self.environment == "production" and self.state_backend != "redis":
            raise ValueError("production requires the Redis state backend")
        return self
