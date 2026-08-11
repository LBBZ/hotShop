from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram


class AgentMetrics:
    def __init__(self) -> None:
        self.registry = CollectorRegistry()
        self.model_tokens = Counter(
            "hotshop_agent_model_tokens_total",
            "Model token usage",
            ("provider", "model", "direction"),
            registry=self.registry,
        )
        self.model_cost = Counter(
            "hotshop_agent_model_estimated_cost_usd_total",
            "Estimated model cost in USD",
            ("provider", "model"),
            registry=self.registry,
        )
        self.active_runs = Gauge(
            "hotshop_agent_active_runs",
            "Currently active model runs",
            registry=self.registry,
        )
        self.run_outcomes = Counter(
            "hotshop_agent_run_outcomes_total",
            "Agent run outcomes",
            ("outcome",),
            registry=self.registry,
        )
        self.latency = Histogram(
            "hotshop_agent_latency_seconds",
            "End-to-end Agent run latency",
            ("provider", "model"),
            registry=self.registry,
        )
        self.provider_requests = Counter(
            "hotshop_agent_provider_requests_total",
            "Model provider request outcomes",
            ("provider", "model", "outcome"),
            registry=self.registry,
        )
        self.tool_calls = Counter(
            "hotshop_agent_tool_calls_total",
            "Agent tool call outcomes",
            ("tool", "outcome"),
            registry=self.registry,
        )
        self.errors = Counter(
            "hotshop_agent_errors_total",
            "Bounded Agent error categories",
            ("type",),
            registry=self.registry,
        )
        self.circuit_state = Gauge(
            "hotshop_agent_circuit_state",
            "1 while the model circuit is open or half-open",
            registry=self.registry,
        )
        self.rag_requests = Counter(
            "hotshop_agent_rag_retrieval_total",
            "Static knowledge retrieval outcomes",
            ("outcome",),
            registry=self.registry,
        )
        self.rag_latency = Histogram(
            "hotshop_agent_rag_retrieval_seconds",
            "Static knowledge retrieval latency",
            ("outcome",),
            registry=self.registry,
        )
        self.embedding_requests = Counter(
            "hotshop_agent_embedding_requests_total",
            "Embedding request outcomes",
            ("provider", "outcome"),
            registry=self.registry,
        )
        self.index_documents = Gauge(
            "hotshop_agent_rag_index_documents",
            "Documents in the latest successful rebuild",
            registry=self.registry,
        )
        self.index_chunks = Gauge(
            "hotshop_agent_rag_index_chunks",
            "Chunks in the latest successful rebuild",
            registry=self.registry,
        )
        self.rebuilds = Counter(
            "hotshop_agent_rag_rebuild_total",
            "Knowledge rebuild outcomes",
            ("outcome",),
            registry=self.registry,
        )
