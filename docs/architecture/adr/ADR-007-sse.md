# ADR-007：HTTP 写入与 SSE 单向进度

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

订单进度和 Agent 输出由服务端单向推送；浏览器写操作已有 REST 与认证/幂等约束，不需要全站双向长连接。

## 决策

普通请求用 REST，订单进度和 Agent run 使用 SSE。订单有持久 timeline 与重连查询；Agent 输出只有结构化事件、摘要和最终回答，运行 task 与流队列在本进程内，取消时清理 provider。

## 取舍与限制

必须处理断线、认证过期、背压与取消。订单 timeline 与 Agent stream 的恢复能力不同：不能把短期 Redis 会话误写为任意进程的流恢复，SSE 也不保证事件只交付一次。客户端仍须按事件协议检查并重新查询业务事实。

## 实现证据

[TransactionEventStreamService](../../../portal/src/main/java/com/real/portal/sse/TransactionEventStreamService.java)、[Agent service](../../../agent/src/hotshop_agent/service.py)、[Agent 进程](../agent-process.md)。
