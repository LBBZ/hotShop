# TASK-16 verification

## 结论

TASK-16 服务端范围已实现：固定 Agent 工具注册表、逐次身份与 scope 校验、严格参数
Schema、最小化结构化结果、追加式审计、购买草稿，以及由 User 明确确认后在 Java/MySQL
事务中原子消费的一次性确认令牌。未实现 Agent 前端或生成 TypeScript 客户端，也未启动
TASK-17。

基线提交为 `330fbf6bb14eb004a655856affd68127edb675d3`。最初 TASK-16 开始时工作树为空；
`TASK-16-RECONCILE-01` 开始时工作树已同时包含 TASK-16 与并行 TASK-14/W11 的未提交改动，
reconcile 没有回退或覆盖后两者。验证使用 Docker 中的 Python 3.12、Java 21，以及真实
Docker Compose；业务事实测试使用 MySQL 8.0.46 Testcontainers，Portal 集成测试同时使用
Redis 8.8.1。

## TASK-16-RECONCILE-01 根因与修复

1. Agent 镜像的业务进程使用 UID/GID `10001`，但 Windows 上由 root 容器生成并以只读
   bind mount 挂载的 `agent-service-private.pem` 是 `root:root 0600`，导致真实 session
   创建在签发 client assertion 时触发 `PermissionError`。runtime 镜像现在只在入口阶段
   以 root 校验非空只读源，将其复制到 tmpfs `/run/hotshop-agent`，设置
   `10001:10001 0400`，随后用 `setpriv` 清空附加组和 capabilities、启用
   `no-new-privs` 并 `exec` Uvicorn。源缺失、空文件、非 root 启动或权限设置失败均以
   exit `70` fail-fast；public keys 仍是只读挂载，密钥未写入镜像。
2. Java token exchange DTO 原 scope 正则不允许 segment 中的连字符，因此拒绝双方固定
   allowlist 中的 `purchase-drafts:create`。正则改为每个 segment 仅允许小写字母/数字，
   可含由单个连字符分隔的非空片段；空 segment、首尾/连续连字符、大写、空格、斜杠和
   其他符号继续拒绝。真实 POST 使用四项 scope，响应和 Delegation Token 保留完全相同集合。
3. Python `ADMIN_AUTHORITIES` 漏掉 Java 实际签发的
   `PERM_ADMIN_FLASH_SALE_LOAD`，精确集合校验因此拒绝真实 Administrator Token。集合已与
   Java 同步；issuer、audience、`typ`、`token_use`、`kid`、签名、有效期及精确 authority
   校验未弱化。Admin HTTP session 入口覆盖完整集合成功，以及缺 authority、额外 authority、
   User/Delegation Token、篡改签名、错误 audience 和错误 `token_use` 失败。
4. `IdentitySecurityTest` 仍期待 V1.7 时的 8 个迁移，使 V1.8 干净迁移回归失败。断言已同步
   为 9 个迁移且当前版本 `1.8`，仍保留第二次 migrate 执行数 `0` 和 validate 成功断言，
   未删除或跳过该类任何测试。

此外，FakeModel 在工具成功后的第二轮现在只把标记为 untrusted 的结构化工具结果当作数据，
确定性返回安全 JSON。购买草稿会向用户展示 `draftId`、`CREATE_ORDER`、商品/数量、单价与
总价快照、币种、有效期、`confirmationRequired` 和后续确认提示；确认令牌等敏感 key 会过滤，
不会因商品文本中的提示词再次调用工具。

## 固定工具与权限

| 工具 | 身份 | scope | 固定 Java 路径 |
|---|---|---|---|
| `search_products` | Agent Delegation | `catalog:read` | `GET /agent/api/v1/tools/products` |
| `get_product` | Agent Delegation | `catalog:read` | `GET /agent/api/v1/tools/products/{productId}` |
| `compare_products` | Agent Delegation | `catalog:read` | `POST /agent/api/v1/tools/product-comparisons` |
| `list_my_orders` | Agent Delegation | `orders:self:read` | `GET /agent/api/v1/tools/orders` |
| `list_my_reservations` | Agent Delegation | `reservations:self:read` | `GET /agent/api/v1/tools/reservations` |
| `create_purchase_draft` | Agent Delegation | `purchase-drafts:create` | `POST /agent/api/v1/tools/purchase-drafts` |
| `read_statistics` | Administrator Access | `ROLE_ADMIN` | `GET /admin/api/v1/agent-tools/statistics` |
| `read_anomaly_summary` | Administrator Access | `ROLE_ADMIN` | `GET /admin/api/v1/agent-tools/anomalies` |
| `create_configuration_draft` | Administrator Access | `ROLE_ADMIN` | `POST /admin/api/v1/agent-tools/configuration-drafts` |

User Access、Administrator Access 与 Agent Delegation 不能互换。购买确认的签发、撤销和消费
只接受 User Access，并位于 `/api/v1/orders/purchase-drafts/**` 与
`/api/v1/orders/purchase-confirmations/**`。注册表不存在任意 SQL、URL、Shell、文件、动态
工具、高风险管理操作或运行时 scope 扩展。

## 确认状态与原子性

购买草稿只保存商品/数量、名称和价格快照、参数摘要及有效期；不扣库存、不创建订单或预约，
不锁商品。确认令牌是 256-bit CSPRNG opaque 值，数据库仅保存 SHA-256 hash，并绑定 User、
`CREATE_ORDER`、规范化商品/数量摘要、canonical JSON、签发/过期时间、唯一 nonce 与状态。

确认状态为 `ISSUED -> CONSUMED | REVOKED | EXPIRED`。消费事务先 `SELECT ... FOR UPDATE`，
再校验 User、草稿、动作、参数、状态和过期时间，条件更新为 `CONSUMED`，随后调用既有实时
下单交易服务。订单、库存、Outbox/时间线和成功审计与确认状态共用事务；任一失败全部回滚。

## 自动化结果

### Python Agent

```text
docker build --target test -t hotshop-agent:task16-reconcile-test agent
docker run --rm hotshop-agent:task16-reconcile-test python -m pytest
docker run --rm hotshop-agent:task16-reconcile-test python -m ruff check src tests
docker run --rm hotshop-agent:task16-reconcile-test python -m mypy --strict src/hotshop_agent

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/agent:/workspace:ro" -e AGENT_CONTAINER_IMAGE=hotshop-agent:task16-reconcile \
  docker:29-cli sh -lc \
  "apk add --no-cache python3 py3-pytest >/dev/null && \
   cp /workspace/tests/test_container_security.py /tmp/test_container_security.py && \
   cd /tmp && python3 -m pytest test_container_security.py"
```

结果：`171 passed, 6 skipped`；6 项 skip 仅是主 pytest 容器没有宿主 Docker socket，随后以
独立 Docker CLI runner 执行同一 `test_container_security.py`，结果 `6 passed`。Ruff
`All checks passed!`；strict mypy `Success: no issues found in 20 source files`。FakeModel 旅程通过真实 TCP HTTP 调用固定后端，
覆盖完整 LangGraph 工具节点、token exchange、SSE 最小化和敏感值不泄漏；提示词注入、动态
工具、SQL、URL、Shell、退款等请求均在后端调用前拒绝。

### Java Portal 与确认链路

```text
$env:JAVA_HOME='D:\Env\JDK\MicrosoftOpenJDK21'
.\mvnw.cmd -B -pl portal -am \
  '-Dtest=AgentToolsAndPurchaseConfirmationIntegrationTest,IdentitySecurityTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`19 tests, 0 failures, 0 errors, 0 skipped`，其中 Agent/购买链路 `8` 项、完整
`IdentitySecurityTest` `11` 项。覆盖全部合法 User 工具、本人资源隔离、
issuer/audience/azp/token type/scope 错误、严格 Schema、草稿无交易副作用、签发并成功下单、
并发单次消费、重放、跨 User、参数篡改、过期、撤销、错误动作、事务故障同步回滚，以及审计
和确认明文未落库/未入审计。

### Java Administrator 工具

```text
$env:JAVA_HOME='D:\Env\JDK\MicrosoftOpenJDK21'
.\mvnw.cmd -B -pl admin -am \
  '-Dtest=AdminAgentToolsControllerTest,AdminAgentToolServiceTest,AdminIdentitySecurityTest' \
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`31 tests, 0 failures, 0 errors, 0 skipped`，其中 Admin identity `16` 项、controller
`2` 项、service `13` 项。覆盖固定低风险查询、严格配置草稿、
额外/重复/尾随字段拒绝、类型与范围校验、固定 SQL 和追加式脱敏审计。

### Flyway V1.8 与 MySQL 约束

```text
$env:JAVA_HOME='D:\Env\JDK\MicrosoftOpenJDK21'
.\mvnw.cmd -B -pl database test
```

结果：`39 tests, 0 failures, 0 errors, 0 skipped`。覆盖 V1.8 fresh migrate、validate、
重复 migrate、旧基线升级、JSON/状态约束和全库 `0` foreign key。

### 其他门禁

- `docker compose --env-file .env.example config --quiet`：通过。
- 运行时 OpenAPI：由打包后的 Portal Java 21 容器抓取 `user` 文档并 canonicalize；基线仅更新
  `docs/api/openapi-baseline/user.json`，未生成或手改 TypeScript 客户端。
- `python script/check_openapi_compatibility.py`：通过。
- `git diff --check`：通过。

## 真实 Docker Compose 部署验证

执行 `script/verify-task16-compose.ps1 -TimeoutSeconds 600`，脚本每次生成随机 project 名、
独立 volume、临时镜像标签和临时 RSA keys。本轮最终成功 project 为
`hotshop-task16-f4bf526cd4`，provider 为 `fake`；验证结束后该 project 的 containers、network、
volumes、三张临时业务镜像和临时 key 目录均已删除。脱敏证据保存在
`target/task16-compose-evidence/hotshop-task16-f4bf526cd4`。

部署与身份证据：database migrator 将干净库迁移至 `1.8` 且全库 foreign key 为 `0`；Agent
业务 PID effective UID 为 `10001`，运行时私钥是 `10001:10001 0400`，UID `10001` 可读、
UID `10002` 不可读，生成的私钥精确字节不在 runtime image 或容器 inspect 中。用户注册返回
`201`、登录返回 `200`，显式携带四项 scope 创建 Agent session 返回 `201` 且响应 scope
完全一致；真实 Java token exchange 签发的 Delegation Token 由 Python 再次验证后才调用工具。

用户 Agent 旅程中，message/run/SSE 分别返回 `201/202/200`。六个固定用户工具全部成功；
购买草稿 ID 由 SSE 返回并与数据库只读事实交叉校验。草稿创建前后库存保持 `12 -> 12`，订单
保持 `0`，也未创建预约。User 签发确认令牌返回 `200`，数据库只保存其 SHA-256 hash；跨用户、
篡改数量和错误动作分别返回 `409` 且令牌仍为 `ISSUED`。首次合法消费返回 `200`，只创建
`1` 个订单/`1` 个订单项并将库存 `12 -> 10`；相同令牌重放返回 `409`，订单仍为 `1`、库存
仍为 `10`。另一用户通过 Agent 查询不到该订单。

管理员注册后仅由隔离验证器将测试账号设置为 `ROLE_ADMIN`，随后真实 Admin 登录返回 `200`、
Admin Agent session 返回 `201`。`read_statistics`、`read_anomaly_summary` 和
`create_configuration_draft` 的 message/run/SSE 均为 `201/202/200` 且成功，数据库只新增
一条 `LOW/DRAFT` 配置草稿。退款、库存补偿、消息重放、权限变更和用户封禁五类高风险工具，
以及任意 SQL、URL、Shell，均在 Agent SSE 中返回 `TOOL_NOT_ALLOWED`；前后订单、库存、
预约、配置、Outbox 和用户角色快照没有任何高风险副作用。

审计证据包含 `11` 条 `AGENT_TOOL_INVOKED`（用户 `7`、管理员 `3`；另含归属隔离调用）、
确认签发 `1`、拒绝 `4`、消费 `1`。工具与全部确认操作都有非空 requestId 和 32 位 traceId。
日志、审计 state summary、持久化 SSE 与 API 响应均扫描不到 User/Admin Access Token、Delegation
Token、确认令牌、密码、私钥正文、完整 prompt 或思维链。

## 审计与敏感数据

工具审计包含 actor、delegated User、固定工具、资源类型/ID、结果、requestId、traceId、source
和受限参数摘要。确认审计只记录草稿/订单标识、动作、item count 与参数 digest。Token、Cookie、
确认明文、完整 prompt、模型原始输出、思维链、SQL、异常堆栈和密钥不会进入审计或 SSE。

## 已知边界

- 基线仓库没有任务说明要求阅读的 `docs/architecture/audit-log.md`；实现依据现有
  `audit_log` migration、审计服务和本任务新架构文档，未越权新建该缺失文档。
- 当前工作树同时存在另一任务的 TASK-14/W11 Admin/Web 改动；TASK-16 未编辑、回退或计入这些
  外部改动。
- Agent 前端接入与 User TypeScript 客户端生成留给 W11 reconcile，符合任务边界。
