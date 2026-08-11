# ADR-001: Closed multi-model Provider registry

- Status: Accepted
- Date: 2026-08-11

## Context

HotShop needs a recommended real chat model without coupling Agent policy, RAG, tools, or
authorization to one vendor. Tests must remain deterministic and must not require paid keys.
Embedding is a different capability with different vendors and batching constraints.

## Decision

Use one provider-neutral `ModelProvider` protocol and a read-only factory registry containing
exactly `fake`, `deepseek`, and `qwen`. One process selects one active provider from trusted startup
configuration. DeepSeek (`deepseek-v4-flash`, optionally `deepseek-v4-pro`) is the recommended real
provider; Qwen remains supported. FakeModel is the default for local automation and CI.

DeepSeek and Qwen share a bounded OpenAI-compatible Chat Completions/SSE transport. Their adapters
only supply vendor defaults, request extensions, normalized capability metadata, and pricing.
DeepSeek thinking is explicitly disabled and `reasoning_content` is discarded. There is no native
tool-call redesign: all providers retain the strict provider-neutral JSON tool-selection protocol.

`EmbeddingProvider` remains separate, with deterministic and Bailian implementations. Selecting
DeepSeek does not imply a DeepSeek embedding API. Bailian `text-embedding-v4` advertises a maximum
batch of 10 and the indexer obeys provider capability.

No automatic cross-provider fallback is implemented. It could duplicate cost/output, obscure
failure attribution, and create inconsistent policy behavior. Operators change the active provider
through trusted deployment configuration and restart the process.

## Consequences

Adding a future provider requires a small adapter plus a registry entry and contract tests; no
AgentService, LangGraph, RAG, ToolRegistry, or authorization change is permitted. Provider/model
metric values stay allowlisted. CI remains offline and deterministic with FakeModel, while real
adapters are verified only with MockTransport.
