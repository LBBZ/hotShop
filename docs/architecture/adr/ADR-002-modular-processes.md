# ADR-002：模块化、多进程，保持单仓库

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

项目面向本地演示与可复核的交易一致性；当前团队和交付规模没有拆分自治服务的证据。

## 决策

保留 portal、admin、task 三个 Java 进程及共享 domain/security/infrastructure 模块；Agent 是独立辅助进程，通过固定 HTTP API 调用交易核心，共享交易数据库仍由 Java 管理。不引入服务注册、配置中心或分布式事务框架。

## 取舍与限制

模块复用和单库事务降低演示及一致性验证成本；共享数据库和发布依赖仍存在，进程可独立运行不代表独立自治。未来拆分须重新证明边界、成本和运维能力，不能在简历中称为微服务。

## 实现证据

[根 pom](../../../pom.xml)、[Compose](../../../docker-compose.yml)、[Agent registry](../../../agent/src/hotshop_agent/registry.py)；API 调用失败边界另见 [Agent 进程](../agent-process.md)。
