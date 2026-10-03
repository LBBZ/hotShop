# HotShop Agent 进程运行手册

## 1. 边界与默认模式

Agent 是独立 Python 3.12 进程，使用 FastAPI/LangGraph；依赖以 [pyproject.toml](../../agent/pyproject.toml)
和 requirements lock 文件为准。它使用 `redis-cache` 保存可淘汰的短期会话状态，
不访问 MySQL、`redis-seckill`、RabbitMQ，也不加入服务注册、配置中心或消息总线。Agent、模型 Provider 或
检索进程故障不需要停止 Java 的普通交易和查询入口。`redis-cache` 是共享基础设施，其故障还可能影响
Java 的认证/交易限流，不能视为仅影响 Agent。

Qdrant 是仅用于 FAQ、售后政策和静态活动规则的可选 Agent 依赖。动态价格、库存、可售性、订单、
预约和支付事实始终走固定 Java 工具。Qdrant 故障只让静态问题明确降级，动态工具仍可运行；详见
`docs/runbooks/agent-rag.md`。

默认 `AGENT_MODEL_PROVIDER=fake`。FakeModel 不读取付费模型 Key，输出固定且可重复，适用于本地、测试
和 CI。推荐的真实 Provider 是 DeepSeek：设置 `AGENT_MODEL_PROVIDER=deepseek` 并从进程环境注入
`AGENT_DEEPSEEK_API_KEY`；模型默认 `deepseek-v4-flash`，可信配置可改为 `deepseek-v4-pro`。Qwen 仍受
支持：设置 `AGENT_MODEL_PROVIDER=qwen` 并注入独立的 `AGENT_QWEN_API_KEY`。只校验活动 Provider 的
Key，不按请求切换，也不自动 fallback。Key 不得写入仓库、镜像、日志或请求。

DeepSeek 请求显式携带 `thinking={"type":"disabled"}`，返回中的 `reasoning_content` 被丢弃。Qwen
请求不携带该字段。模型 Provider 与 `AGENT_EMBEDDING_PROVIDER=deterministic|bailian` 完全独立。

## 2. 本地启动

完整浏览器演示优先使用 [隔离启动入口](container-environment.md)。下方是从仓库根目录执行的组件
手动启动参考；`.env.example` 中的凭据只适合本机一次性验证。先生成本地认证密钥：

```powershell
.\script\generate-auth-keys.ps1
```

启动 Java 应用和 Agent 使用两个 profile：

```powershell
docker compose --env-file .env.example --profile app --profile agent up -d --build
```

只启动 Agent 与其状态/知识依赖（用户工具仍需要 Java 后端）：

```powershell
docker compose --env-file .env.example --profile agent up -d --build redis-cache qdrant agent-service
```

首次启动或知识变更后执行原子索引：

```powershell
docker compose --env-file .env.example --profile agent exec -T agent-service python -m hotshop_agent.index_cli validate
docker compose --env-file .env.example --profile agent exec -T agent-service python -m hotshop_agent.index_cli rebuild
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8090/health/live
Invoke-RestMethod http://localhost:8090/health/ready
Invoke-WebRequest http://localhost:8090/metrics
```

`/health/live` 仅证明进程事件循环存活。`/health/ready` 只检查状态存储；Redis 不可用时返回 503。
READY 不证明付费 Provider、Java 工具或 Qdrant 可用，需分别验证相关功能。

## 3. 密钥挂载

Agent 容器只读挂载：

- Agent Service assertion 私钥；
- User Access 公钥；
- Administrator Access 公钥；
- Agent Delegation 公钥。

不得向 Agent 挂载 User、Administrator 或 Agent Delegation 私钥。Agent Service 私钥只属于 Python
进程；Java 只持有对应公钥，浏览器不接触 assertion 或任何私钥。

用户建立会话时，Agent 验证 User Access，生成最长 60 秒且 `jti` 每次唯一的 `client-auth+jwt`，
调用 Java `/agent/api/v1/auth/token-exchange`，再验证返回的短期 Agent Delegation。原始 User Access、
client assertion 和 Delegation 都不写入会话存储。管理会话只验证 Administrator Access，不执行
token exchange，不获得 Agent Delegation。连续提问每个 User run 重新交换 Delegation，不复用过期授权。

换票失败返回 503 时，查询 `event=agent.token_exchange.failed` 并关联 `requestId`。
`errorType` 区分连接 / 读取超时、连接失败、传输错误、限流、HTTP 错误和响应校验错误；
日志不包含 Token、assertion、响应正文或异常消息。请求创建失败尚未进入知识检索，不能归因于 Qdrant。

## 4. 状态与 Redis

Docker 固定使用 `redis-cache` DB 0，所有键使用 `hotshop:agent:` 前缀：

- `hotshop:agent:session:<uuid>`：默认 TTL 3600 秒；
- `hotshop:agent:message:<uuid>`：默认 TTL 3600 秒；
- `hotshop:agent:run:<uuid>`：默认 TTL 900 秒。
- `hotshop:agent:history:<session-id>`：最多 12 轮已完成问答，追加时使用 session TTL；模型上下文另限 24,000 字符。

这些状态可淘汰，丢失后需要开始新对话，不是交易事实来源。对话历史包含问答内容，应按短期用户数据管理；
Token、assertion、一次性确认值不进入状态存储。测试使用内存实现。

## 5. 取消、超时与故障

- `DELETE .../runs/{runId}` 显式取消运行；
- SSE 客户端断开会取消对应 asyncio task，并关闭 provider 流；
- 进程关闭会取消所有仍在运行的模型 task；
- 每个 SSE queue 固定 128 槽；普通 delta 在队列满时施加背压，不增加无限缓存；
- 取消、失败和完成的终态事件使用非阻塞强制投递，优先淘汰最旧 delta，为 `error`/`done`
  或 `message.completed`/`done` 留出空间；
- run task 结束前必须完成 provider `aclose`、并发额度释放、终态持久化、active-run 指标回收和
  done event 设置；没有 SSE 消费者也遵守该顺序；
- 单次模型流默认 15 秒超时；
- 只在尚未输出任何 delta 时重试临时网络错误、408、429 和指定 5xx；
- 永久错误和已开始输出的流不重试，避免重复内容和重复费用；
- 连续失败达到阈值后熔断；恢复窗口后只允许一个探测；
- 全局和按 User 并发超限会立即拒绝该运行。

追踪上报最多同时执行 2 个请求，每次超时 2 秒；收集器请求失败后退避 30 秒。
达到并发上限或处于退避期时丢弃待上报 span，不积压队列。追踪是尽力交付，收集器不可用时可能缺失，
应结合结构化日志诊断，不能把缺少 trace 当成请求未发生。

SSE 和日志不得包含系统策略、隐藏推理、Authorization、JWT、API Key、Cookie、client assertion、
私钥或完整模型请求。事件类型和字段由代码白名单限制。`StreamingSanitizer` 跨模型 chunk 保留
有限候选状态，覆盖 Bearer、JWT、敏感赋值、PKCS#8/RSA/EC/OPENSSH/ENCRYPTED 私钥和
thinking/analysis/system 标签。候选最多 4096 个 Unicode 字符；超限 fail closed 为
`[REDACTED]`。正常结束显式 flush，取消或异常丢弃未决候选而不输出。

## 6. 验证命令

```powershell
docker build --target test -t hotshop-agent:test -f agent/Dockerfile agent
docker run --rm --entrypoint python hotshop-agent:test -m ruff check .
docker run --rm --entrypoint python hotshop-agent:test -m ruff format --check .
docker run --rm --entrypoint python hotshop-agent:test -m mypy --no-incremental src tests
docker run --rm --network none -e AGENT_MODEL_PROVIDER=fake -e AGENT_EMBEDDING_PROVIDER=deterministic --entrypoint python hotshop-agent:test -m pytest -m 'not qdrant' -p pytest_asyncio.plugin -p no:cacheprovider
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example --profile agent config --quiet
```

CI 必须保持 `AGENT_MODEL_PROVIDER=fake`；DeepSeek、Qwen 和 Bailian 测试只使用
`httpx.MockTransport`，不得配置真实 Key。
