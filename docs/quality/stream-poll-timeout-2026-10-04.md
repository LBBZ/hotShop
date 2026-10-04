# Stream 空闲轮询与超时校验

本轮修复空闲读取反复计为消费失败的问题，验证配置绑定、消息到达和 Redis 中断恢复。
没有执行压测、容量测试或长时间负载。结构化结果见[验证摘要](stream-poll-timeout-2026-10-04.json)。

## 版本与问题

- 生产代码基线：`827f493e4ece255e6f2a44c7ce8d45fb593e7218`。
- 实施提交：`763f9d3f1ecc68fcc91d8288dec419452efd5809`；随后的审计文档不改变运行代码。
- [上轮审计](conservation-cycles-2026-10-04.md)观察到的空闲超时，在本轮独立 Redis 中复现。

基线的 Compose、`.env.example` 与 Java 属性默认阻塞读取 2 秒，Lettuce 命令超时也为 2 秒；
应用 YAML 的回退值却是 1 秒。Redis 阻塞期限届满后仍需调度并返回结果，客户端可能先超时。
红测中，两次空闲读取使 `hotshop.seckill.order.processing_failures` 从 0 增至 2。

旧校验还允许 `500us`，但阻塞时长传输时截断为毫秒，0 意味着无限等待。
机制参考 Redis 的[阻塞读取说明](https://redis.io/docs/latest/commands/xread/#blocking-for-data)
与 Lettuce 的[命令超时说明](https://github.com/redis/lettuce/wiki/Frequently-Asked-Questions)。

## 修复与升级

Java 属性、应用 YAML、Compose 和环境示例的阻塞默认值统一为 **1 秒**，命令超时保持 **2 秒**。
消费者构造时检查实际 Lettuce 连接工厂配置，要求命令超时比阻塞时长至少长 **1 秒**，
并拒绝小于 1 毫秒的阻塞值。1 秒余量是本项目策略，不是 Redis 协议要求。

显式使用旧 `2s / 2s` 组合的部署需改为 `1s / 2s`，或使用 `2s / 3s`。
不安全的组合使消费者启动失败，错误显示配置名与实际值；禁用消费者时不检查未使用的超时组合。
见[容器配置](../runbooks/container-environment.md)和[Stream 架构](../architecture/stream-order-processing.md)。

真正的依赖故障继续计错和记录日志；Pending 恢复、建单与 ACK 顺序沿用既有协议。

## 红绿回归

基线生产代码加上同一批新增测试：**13 项，6 通过、7 失败、0 错误、0 跳过**。
失败是默认值不一致、4 组危险超时组合被接受、亚毫秒值被接受，以及空闲读取计错。
零值 / 负值拒绝、合法自定义组合、禁用消费者、消息到达和中断恢复在基线已通过。

修复后 **48 项通过，0 失败、0 错误、0 跳过**。完成时间：`2026-10-03T23:51:14Z`
（北京时间 2026-10-04 07:51:14）。

| 测试类 | 数量 | 验证范围 |
| --- | --- | --- |
| `SeckillOrderPollingConfigurationTest` | 10 | 真实 Spring 属性绑定和生产连接配置；默认值、边界、非法组合、禁用消费者 |
| `ReservationStreamPollingContainerTest` | 3 | 两次空闲读取、阻塞期间到达一条消息、Redis 暂停后恢复 |
| `SeckillOrderReliabilityContainerTest` | 31 | 既有真实 MySQL / Redis 建单、幂等、补偿、故障恢复与守恒回归 |
| `ReservationStreamLagTest` | 3 | 积压观测回归 |
| `ReservationStreamConsumerObservationTest` | 1 | 观测失败隔离回归 |

新增传输测试使用真实 Redis、生产连接工厂和消费者，模拟订单处理及预约证明服务；
断言处理一次、PEL 清空、原始 Stream 事件保留。它们不单独证明 MySQL 建单，数据库行为由上述
31 项既有回归覆盖。中断测试只暂停自己的 Redis，并在 `finally` 中恢复。预期故障日志仍保留。

本机采用 Java 21、缓存的 Maven Wrapper、Redis `8.8.3-alpine` 和 MySQL `8.4.11`。
红测仅选择前两个新增测试类；绿测命令如下：

[本地运行入口](../delivery/demo.md)

原始日志、红绿 Surefire XML、源码 SHA256 和汇总在本机忽略目录
`.local/verification/stream-poll-timeout-20261004/`；含环境属性的原始 XML 不发布到仓库。

## 执行边界

Compose 解析确认默认组合 `1s / 2s`。本轮未启动已停止的演示服务，也未重新部署演示容器。
当前两套演示的环境文件没有显式覆盖这些参数，下次重建 Task 时采用新默认值。
测试容器由 Testcontainers 与 `--rm` 回收，保留原有数据卷。

1 秒余量不保证严重网络延迟、服务停顿或 Redis 不可用时没有超时，此类异常继续报告。
本轮没有吞吐、延迟分位数或容量结论。“不压测”的持续执行约束已记录在[后续工作](../delivery/next-iteration.md)。
