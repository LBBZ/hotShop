from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge


class AgentMetrics:
    def __init__(self) -> None:
        self.registry = CollectorRegistry()
        self.model_tokens = Counter(
            "hotshop_agent_model_tokens_total",
            "Model token usage",
            ("provider", "direction"),
            registry=self.registry,
        )
        self.model_cost = Counter(
            "hotshop_agent_model_estimated_cost_usd_total",
            "Estimated model cost in USD",
            ("provider",),
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
