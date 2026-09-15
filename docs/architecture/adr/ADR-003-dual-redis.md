# ADR-003：两个独立 Redis 实例

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

缓存与限流数据的可重建性不同于库存预约和未落库 Stream 承诺。一个实例的淘汰策略不能同时适配两类数据。

## 决策

redis-cache 与 redis-seckill 是独立进程，均只用 DB 0。cache 可淘汰；seckill 使用 noeviction、AOF 与 RDB、独立持久卷。不要用逻辑 DB 号或动态 RedisTemplate 工厂模拟物理隔离。

## 取舍与限制

增加一个进程及卷的维护成本；noeviction 只防内存淘汰，不保证掉电零丢失。当前是本地单实例拓扑，没有 Redis HA/Sentinel/Cluster 承载证明；Lua 使用统一 hash tag 也不代表已部署集群。

## 实现证据

[Compose](../../../docker-compose.yml)、[infrastructure](../../../infrastructure/src/main)、[预约架构](../flash-sale-reservation.md)。
