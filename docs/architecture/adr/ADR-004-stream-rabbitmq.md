# ADR-004：Stream 接入与 RabbitMQ 后续事件分工

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

秒杀需要原子预约、库存预扣及可恢复事件；订单事务后的业务事件、超时则需要数据库持久意图和独立消费路径。

## 决策

入口 Redis Lua 在一个调用中完成校验、预约、预扣和 XADD；Task 用消费者组和 Pending 认领转单。RabbitMQ 负责已持久化 Outbox 的业务事件、模拟回调、固定超时 TTL + DLX，不安装 delayed-message 插件。

## 取舍与限制

维护两种消息机制及跨存储恢复证据；Stream ACK 不删除历史，RabbitMQ delay 队列保留未到期消息是正常行为。不能因为 ready 队列为空推导所有链路清空。

## 实现证据

[ReservationStreamConsumer](../../../task/src/main/java/com/real/task/seckill/ReservationStreamConsumer.java)、[OutboxPublisher](../../../task/src/main/java/com/real/task/outbox/OutboxPublisher.java)、[可靠消息](../reliable-messaging.md)。
