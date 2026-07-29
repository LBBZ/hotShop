# HotShop Agent 进程运行手册

## 1. 边界与默认模式

Agent 是独立 Python 进程，不是交易核心的一部分。它只依赖 `redis-cache` 保存可淘汰的短期会话状态，
不访问 MySQL、`redis-seckill`、RabbitMQ，也不加入服务注册、配置中心或消息总线。Agent、Qwen 或
`redis-cache` 故障时，停止或降级的是 Agent 入口；Java 的普通交易和查询入口不依赖该进程。

默认 `AGENT_PROVIDER=fake`。FakeModel 不读取付费模型 Key，输出固定且可重复，适用于本地、测试和 CI。
只有显式设置 `AGENT_PROVIDER=qwen` 时才要求从进程环境读取 `AGENT_QWEN_API_KEY`。Key 不得写入仓库、
镜像、日志或请求。

## 2. 本地启动

先生成本地认证密钥：

```powershell
.\script\generate-auth-keys.ps1
```

启动 Java 应用和 Agent 使用两个 profile：

```powershell
docker compose --env-file .env.example --profile app --profile agent up -d --build
```

只验证 Agent 镜像和 Redis 状态后端：

```powershell
docker compose --env-file .env.example --profile agent up -d --build redis-cache agent-service
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8090/health/live
Invoke-RestMethod http://localhost:8090/health/ready
Invoke-WebRequest http://localhost:8090/metrics
```

`/health/live` 仅证明进程事件循环存活。`/health/ready` 会检查状态存储；Redis 不可用时返回 503。

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
token exchange，不获得 Agent Delegation。

## 4. 状态与 Redis

Docker 固定使用 `redis-cache` DB 0，所有键使用 `hotshop:agent:` 前缀：

- `hotshop:agent:session:<uuid>`：默认 TTL 3600 秒；
- `hotshop:agent:message:<uuid>`：默认 TTL 3600 秒；
- `hotshop:agent:run:<uuid>`：默认 TTL 900 秒。

这些状态可删除、可重建，不是交易事实来源。测试使用内存实现。

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

SSE 和日志不得包含系统策略、隐藏推理、Authorization、JWT、API Key、Cookie、client assertion、
私钥或完整模型请求。事件类型和字段由代码白名单限制。`StreamingSanitizer` 跨模型 chunk 保留
有限候选状态，覆盖 Bearer、JWT、敏感赋值、PKCS#8/RSA/EC/OPENSSH/ENCRYPTED 私钥和
thinking/analysis/system 标签。候选最多 4096 个 Unicode 字符；超限 fail closed 为
`[REDACTED]`。正常结束显式 flush，取消或异常丢弃未决候选而不输出。

## 6. 验证命令

```powershell
Set-Location agent
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.lock -e .
.\.venv\Scripts\ruff.exe check .
.\.venv\Scripts\ruff.exe format --check .
.\.venv\Scripts\mypy.exe src
.\.venv\Scripts\pytest.exe
Set-Location ..
docker build -f agent/Dockerfile agent
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example --profile agent config --quiet
```

CI 必须保持 `AGENT_PROVIDER=fake`；Qwen 测试只使用 `httpx.MockTransport`，不得配置真实 Key。
