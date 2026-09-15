# 分页守恒去重补修（2026-09-16）

本次从集成基线 `ff7e65ff6c1a1a03f70b5b6d0f6906b3d849397a` 创建独立 worktree / 分支 `review-fix-conservation-dedup`。补修之前 IR-04 分页实现遗漏的预占号去重：Stream 重投可以有相同或不同 eventId，但同一 reservationNo 只能贡献一次有效预占数量。

## 设计和兼容性

- 新增同 activity hash slot 的 Redis `:reconciliation:conservation:seen` hash。有效事实经校验后，以 `HSETNX reservation:<reservationNo>` 决定是否累计；每条重复投递仍验证数量、状态及回补凭证，不跳过异常证据。
- seen 与 checkpoint 在同一个 Lua 调用中推进，无 TTL；Java 服务重建后从持久化 cursor、quantity 和 seen 继续。
- checkpoint 使用 `schemaVersion=2`。遗留格式、seen 丢失、inventory fence 变化均丢弃部分和并从首个有界页面重算；不会混用旧累加值和新去重状态。正常完成也回收 seen，下轮重新计数。
- 回收使用 `UNLINK`，不对集合做全量读/同步遍历。每页仍只有一个 `XRANGE ... COUNT B`、至多 B 个事实 `HMGET`，新增至多 B 个 `HSETNX` 及常数个生命周期命令。去重状态空间随本轮有效的唯一预占数增长，单次读取/处理记录数仍受 B 约束；持续写入导致 fence 不稳定时继续保留原有“需稳定窗口才能完成”的限制。
- 无 SQL migration、API、OpenAPI、生成客户端、前端或 Agent 改动。Redis 新 checkpoint 自动识别旧格式；部署时应整体替换对账 worker，旧程序不理解此去重状态，不能与新程序混跑或回滚后仍宣称修复有效。

## 新增真实回归

`SeckillOrderReliabilityContainerTest` 使用 MySQL 8.0.46 / Redis 8.8.1 Testcontainers、真实 Java service 与生产 Lua：

1. `reviewDedupRepeatedEventAndReservationAcrossPagesSurviveRestart`：B=2，同 eventId 同页重投，不同 eventId 同 reservationNo 跨页重投，原 eventId 再次跨页出现；重建 Java service，断言中间量 2 / 5、最终量 5、无虚假 Redis 守恒告警。下一轮真实库存从 95 改为 96，仍计算 5 并产生 CRITICAL，证据含 currentStock=96 / effectiveReservedQuantity=5，未错误修复库存。
2. `reviewDedupLegacyCheckpointAndLostSeenStateRestartBoundedly`：伪造旧 checkpoint（已误算 quantity=4），断言首个 B=2 页面重算为 2 而非提前 COMPLETE；删除 seen 后再次有界重启，最终为 5、无虚假守恒告警、完成后 seen 已回收。
3. `reviewDedupWriterFenceRestartRecountsPreviouslySeenReservations`：跨页补偿触发 fence 变化，最终只计有效预占 3，无虚假守恒告警。既有 `review04CompensationBetweenPagesRestartsSnapshotAndStillFindsRealTampering` 同时验证 fence 重启时第一页其他仍有效预占会重新计入。

## 修复前证据

在基线生产代码仅加入上述测试后，运行：

```powershell
docker run --rm --name hotshop-review-dedup-red --mount type=bind,source=D:/Codex/Projects/hotShop-review-dedup,target=/workspace --mount type=volume,source=hotshop-task04-m2,target=/root/.m2 --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:21-jdk sh ./mvnw -B -ntp -pl task -am '-Dtest=SeckillOrderReliabilityContainerTest#reviewDedup*' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

3 tests / 2 failures / 0 errors / 0 skipped，BUILD FAILURE：期望 quantity=2 实际4；遗留 checkpoint 期望 IN_PROGRESS 实际 COMPLETE。第三项原本通过，属于防回归覆盖，不冒称失败复现。摘录见 [dedup-red.txt](dedup-red.txt)，完整日志保留在功能 worktree 的 `target/review-dedup/red.log`。容器日志 UTC，主机 Asia/Shanghai；UTC 2026-09-15 对应本地 2026-09-16。未改变时区或版本设置。
