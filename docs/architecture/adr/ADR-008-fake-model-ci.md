# ADR-008：FakeModel 与确定性 Embedding 作为 CI 默认

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

真实模型存在费用、网络、限额与随机性；授权、工具参数、取消、引用协议和回归门禁需要可重复验证。

## 决策

CI 固定 AGENT_MODEL_PROVIDER=fake 与 AGENT_EMBEDDING_PROVIDER=deterministic；聊天模型与 embedding 是独立接口。真实 DeepSeek/Qwen adapter 使用 MockTransport 合约测试；需要真实服务时另外显式配置，不自动跨厂商 fallback。

## 取舍与限制

离线绿色验证协议与控制流，不证明真实模型中文理解、幻觉率或线上质量。确定性检索评测不等于真实 embedding 效果。报告必须标明数据集、版本、provider、排除项；不能把模拟 token 或费用计量说成真实账单。

## 实现证据

[CI workflow](../../../.github/workflows/ci.yml)、[Provider factory](../../../agent/src/hotshop_agent/providers/factory.py)、[Embedding 接口](../../../agent/src/hotshop_agent/embeddings/base.py)、[ADR-001](ADR-001-multi-model-provider.md)。
