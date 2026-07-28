# HotShop 统一审计运行手册

> TASK-06 运行边界。审计日志是调查事实，不是普通业务数据；业务 API 只追加和只读查询。

## 1. 记录模型

每条 `audit_log` 必须包含：

- 直接 actor type/ID；无可识别调用者的登录失败使用 `SYSTEM`，ID 省略；
- 可选 delegated actor type/ID；Agent delegation 签发时直接 actor 是 Service Identity，
  delegated actor 是 User；
- action、resource type/ID、`SUCCESS/FAILURE/DENIED`；
- request ID、trace ID、`PORTAL_API/ADMIN_API/AGENT_API` source；
- UTC `occurred_at` 和脱敏 `state_summary`。

写入接口只接受公共模块定义的枚举、actor/resource 值对象以及封闭的强类型摘要。业务代码不传
JSON、Map 或任意字段名。序列化前仍执行纵深脱敏：password、Access/Refresh Token、API Key、
Cookie、Authorization、client assertion、完整提示词、思维链/推理字段和凭据形态值均替换为
`[REDACTED]`。摘要只保留字段名、计数、生命周期状态和稳定原因码，不保存原始异常消息或请求体。

## 2. 事务与故障语义

管理 Catalog Product 创建、替换和软删除由管理命令服务控制：

1. 业务写和 SUCCESS 审计 INSERT 位于同一个本地 MySQL 事务；
2. 审计 INSERT 报错时，业务写回滚，HTTP 不得返回成功；
3. 业务写报错时，外层业务事务回滚，FAILURE 审计通过 `REQUIRES_NEW` 独立提交；
4. 若数据库整体不可用，业务与失败审计都可能无法提交，但操作仍然失败，不能降级成“无审计成功”。

TASK-05 安全事件保持相同原则：登录成功与 Refresh Session 创建共同提交；refresh reuse 与 family
撤销共同提交；登录失败独立提交；Agent Delegation 的审计写失败会阻止成功响应。

新增 Order、User、支付、补偿或 Agent 写命令时，必须先选择上述“成功同事务、失败独立事务”模板，
再开放端点。不能在 Controller 返回成功后异步补写关键审计。

## 3. 管理员查询

唯一读取端点：

```text
GET /admin/api/v1/audit-logs
```

仅 Administrator Access 可调用。常用查询：

```text
?occurredFrom=2026-07-28T00:00:00Z&occurredTo=2026-07-29T00:00:00Z
?actorType=ADMIN&actorId=42
?action=CATALOG_PRODUCT_UPDATED&resourceType=CATALOG_PRODUCT&resourceId=1001
?result=FAILURE
?limit=100&cursor=<opaque>
```

排序固定为 `occurredAt DESC,auditId DESC`。游标绑定全部筛选条件；改变筛选后应从第一页重新查询。
调查时优先从告警或 Problem Details 的 request ID/trace ID 定位，再沿 actor、resource 和时间窗口
扩展。不要向用户索要密码、Token、Cookie 或完整 Agent 提示词来“补充审计”。

## 4. 不可变保护与权限

没有 POST、PUT、PATCH、DELETE 或清空审计日志的业务端点。数据库 trigger
`audit_log_prevent_update`、`audit_log_prevent_delete` 对 UPDATE/DELETE 抛出
`audit_log is append-only`。值班人员不得临时禁用 trigger、直接改结果或删除“不好看”的事件。

当前未定义在线清理或保留期。未来若法规/容量要求归档或销毁，必须通过单独评审的 Flyway/离线维护
流程定义完整备份、证据保全、审批与恢复方案；不能复用业务应用身份执行。

## 5. 故障处置

- 管理写返回 5xx 且业务事实不存在：检查同一 request ID 的 FAILURE 事件；存在时说明业务失败审计
  已独立提交。
- 管理写返回 5xx 且没有事件：优先检查 MySQL/audit INSERT 可用性；这仍是 fail-closed，不应重标为成功。
- 查询为空：确认使用 Administrator Access、UTC 时间窗口和未跨筛选复用 cursor。
- trigger 创建失败并出现 MySQL 1419：binary log 环境中的迁移身份缺少 trigger 所需权限；由 DBA
  修正迁移权限/受控配置后重新从备份或干净 schema 执行，不用 `repair` 掩盖失败版本。
- 发现疑似敏感值：立即限制审计读取面、记录 audit ID/request ID，按安全事件处理；不要复制敏感值
  到工单。修复摘要类型/扫描器后，通过受审计的专门数据处置流程处理历史数据。

## 6. 验证命令

```powershell
docker compose --env-file .env.example run --rm database-migrator migrate
docker compose --env-file .env.example run --rm database-migrator validate
python .\script\check_openapi_compatibility.py
```

自动化测试还必须证明空库/legacy/重复迁移、0 个 referential constraint、UPDATE/DELETE 阻断、
查询权限和过滤/游标、成功/失败/审计不可用事务语义以及敏感值扫描。
