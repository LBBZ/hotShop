# ADR-005：不使用数据库外键

- 状态：Accepted（现有决策的交付补录）
- 记录日期：2026-09-16
- 核对基线：`10d82528aab71766ab5aa6020180ae4935f96359`

## 背景

总纲明确禁止外键与级联删除，同时交易、消息及审计需要可演进的数据结构和可调查的历史记录。

## 决策

Flyway 为唯一结构源；用主键、业务唯一键、NOT NULL、CHECK 和精确金额类型限制局部事实，应用在事务中校验引用、归属和状态，软删除不回收身份名称。对账检查孤儿引用与跨存储事实。

## 取舍与限制

外键原本提供的引用完整性责任转移到应用和验证；CHECK 不能校验任意跨表关系。绕开应用的 SQL 可制造孤儿记录；无外键不是数据完整性天然保证，也不能凭此声称更高吞吐。

## 实现证据

[迁移目录](../../../database/src/main/resources/db/migration)、[OrderStateService](../../../domain/src/main/java/com/real/domain/service/advance/OrderStateService.java)、[SeckillReconciliationService](../../../task/src/main/java/com/real/task/seckill/SeckillReconciliationService.java)。
