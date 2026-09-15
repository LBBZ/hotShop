# ADR-006：Transactional Outbox 与 Inbox

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

业务提交后直接发布会出现消息丢失；发布后提交又可能重复。MySQL 与 RabbitMQ 之间没有原子提交。

## 决策

业务事实与 Outbox 同事务落库；Task 短事务领取租约后再进行网络发布，token/version fencing 阻止旧 owner 更新。broker ACK 且无 mandatory return 才标为 PUBLISHED。订单超时消费者把 processed_event 唯一键与业务效果同事务提交，提交后 ACK；模拟支付回调则以 Portal callback ledger 保护业务幂等，HTTP 2xx 后 ACK。瞬时回调失败先 confirmed persistent retry publish，再 ACK 原消息；4xx、毒消息或重试耗尽执行 reject/DLX。不能把不同消费者一概描述为同一个 Inbox 事务。

## 取舍与限制

至少一次投递仍可能重复；Inbox 只覆盖指定 consumer/event 身份，其他重复业务由订单、预约、回调唯一键与条件状态更新保护。历史去重证据若删除，保证边界会改变。DLQ、FAILED 和人工重放需要调查，不能宣称 exactly-once。

## 实现证据

[OutboxMapper](../../../domain/src/main/java/com/real/domain/messaging/OutboxMapper.java)、[OutboxPublisher](../../../task/src/main/java/com/real/task/outbox/OutboxPublisher.java)、[OrderTimeoutService](../../../task/src/main/java/com/real/task/timeoutOrderTask/OrderTimeoutService.java)。
