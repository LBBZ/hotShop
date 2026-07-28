# HotShop 认证、密钥轮换与故障运行手册

> TASK-05 运行边界。本文只覆盖 User/Administrator Access、Agent token exchange、Refresh Session、
> Redis 认证安全状态与密钥操作，不扩展审计查询或 Agent 业务工具。

## 1. 运行时密钥边界

HotShop 固定使用 RS256，并维护四个互不复用的 key set：

| key set | 私钥持有者 | 公钥持有者 | 用途 |
| --- | --- | --- | --- |
| User Access | portal | portal | 签发/验证 User Access |
| Administrator Access | admin | admin | 签发/验证 Administrator Access |
| Agent Delegation | portal token-exchange 边界 | portal | 签发/验证 Agent Delegation |
| Agent Service assertion | 未来 Python Agent Service | portal | Agent Service 证明 Service Identity |

portal 只配置 Agent Service 公钥，不持有其私钥。admin 不拿 User 或 Agent 私钥。仓库内 `task` 进程
直接调用 Java service，不配置任何上述密钥，也不伪造 `/internal/**` HTTP 身份。若未来引入内部
HTTP，必须新增独立 Service Identity issuer/audience/key set，不能复用 User JWT。

私钥不得提交、写入镜像、OpenAPI、日志或 env 文件。Compose 将宿主密钥目录只读挂载到需要的进程；
`.local/keys/` 同时被 Git 和 Docker build context 忽略。生产应使用平台 secret volume 或等价
密钥托管，不使用仓库目录。

## 2. 可重复本地密钥生成

脚本使用 Docker 中固定的 OpenSSL 镜像，不要求宿主安装 OpenSSL，也不要求复制粘贴 PEM：

```powershell
.\script\generate-auth-keys.ps1
```

默认输出到被忽略的 `.local/keys/hotshop`，为四个 key set 分别生成 3072-bit PKCS#8 私钥和 X.509
公钥。若任一目标文件已存在，脚本失败并保持已有文件不变。只有操作者明确决定废弃本地全部旧
Access Token 时，才可显式覆盖：

```powershell
.\script\generate-auth-keys.ps1 -Force
```

不要在共享或生产环境使用 `-Force`。生成后先执行静态配置检查：

```powershell
docker compose --env-file .env.example config --quiet
```

## 3. 零停机 Access key 轮换

每个身份域独立轮换，顺序不可交换：

1. 生成新 key pair 和新 `kid`，保留旧 key。
2. 先向所有验证进程发布新公钥，使验证集合同时包含旧、新 `kid`。
3. 验证新公钥已加载后，再将签名配置切换为新私钥和新 `kid`。
4. 等待旧域最长 Access TTL 加 clock skew：User/Admin 为 15 分钟 + 30 秒，Agent Delegation为
   5 分钟 + 30 秒。
5. 确认没有旧 `kid` 的合法请求后，最后移除旧公钥。

绝不能先切签名私钥再发布验证公钥，也不能只改 `kid` 而复用错误域的 key。Refresh Token 是 opaque
值，不参与 JWT key 轮换。

## 4. Cookie 与浏览器接入

- User cookies：`hotshop_user_refresh`（HttpOnly）和 `hotshop_user_csrf`，path `/api/v1/auth`。
- Administrator cookies：`hotshop_admin_refresh`（HttpOnly）和 `hotshop_admin_csrf`，path
  `/admin/api/v1/auth`。
- 均为 host-only、`SameSite=Strict`；生产 `Secure=true`。
- 本机纯 HTTP 只能通过显式 `HOTSHOP_SECURE_COOKIES=false` 关闭 Secure；该值不得进入生产配置。
- 浏览器对 refresh/logout 读取本域 CSRF cookie，并原样放入 `X-CSRF-Token`；服务端常量时间比较。
- Access Token 只从 login/refresh JSON body 取得并保存在内存，禁止 localStorage。

## 5. Redis 和数据库故障语义

`redis-cache` 保存短 TTL 的限流计数、Agent assertion `jti` 防重放和 Access `jti` denylist。所有 key
都带 `hotshop:auth:` 命名空间，只包含 hash/最小化标识，不包含原 Authorization、JWT、Refresh
Token、cookie、username 或密码。计数用原子 Lua执行 `INCR` 和首次 `EXPIRE`。

认证写入口采用 fail-closed：

- Redis 不可用：login、refresh、token exchange 和需要即时 Access 撤销的 logout 返回脱敏 503
  `SERVICE_UNAVAILABLE`，不会绕过限流/防重放/denylist。
- 达到限额：返回 429 `RATE_LIMITED` 和 `Retry-After`。
- MySQL 不可用：Refresh Session 无法创建/轮换/撤销；不得改为 Redis 或内存 session。

Redis 不是 Refresh Session 的事实来源。MySQL `refresh_token` 行锁、状态和唯一 successor 决定
rotation/reuse 结果；即使 Redis 数据丢失，也不能让已撤销 family 恢复。

### 5.1 可信代理与客户端地址

默认 `HOTSHOP_TRUST_FORWARDED_HEADERS=false`，应用完全忽略 `X-Forwarded-For`。启用时必须同时把
每个可能与应用建立 immediate connection 的边缘/反向代理 IPv4 或 IPv6 字面量加入
`HOTSHOP_TRUSTED_PROXY_ADDRESSES`；hostname、DNS 名称和动态解析结果不允许进入该配置。

边缘代理必须：

1. 在信任边界入口清除外部请求携带的 `Forwarded` 和 `X-Forwarded-For`；
2. 从实际下游连接地址重新建立 `X-Forwarded-For`；
3. 后续每层受信代理只采用 append 模式追加其看到的直接来源，不接受客户端覆盖整条链。

应用不会因为边缘已清理就盲信最左值。只有 immediate peer 本身位于预先规范化的可信代理集合时，
应用才读取唯一一条 `X-Forwarded-For`，将 immediate peer 视为链尾，并从右向左跳过连续可信代理，
选择第一个不可信 IP 作为客户端。因此 `spoofed, real-client` 会稳定解析为 `real-client`。应用对链中
每个元素独立执行无 DNS 的 IPv4/IPv6 字面量校验；hostname、空元素、非法 IP、多行 header、超过
1024 字符或超过 32 hops 时忽略整条 header 并回退 immediate peer。配置变化需要重启进程，以便在
Bean 初始化时重新规范化可信代理集合。

## 6. 安全事件和处置

TASK-05 只写最小事件：登录成功/失败、Agent delegation 签发和 refresh reuse。事件只包含 User ID、
client ID、scope 或 username hash 等脱敏摘要，不含凭据。

检测到 refresh reuse 时，事务将旧 token 标记 `REUSED`、撤销 family 内全部 ACTIVE token，并清除
浏览器 cookies。值班处置：

1. 将 401/reuse 的 request ID、trace ID 和时间交给后续审计调查，不索要原 token。
2. 要求 User 重新登录；不要恢复旧 family。
3. 若怀疑 Access 已泄露且不能等待短 TTL，到 Redis denylist 对其 `jti` hash 做即时撤销。
4. 若怀疑私钥泄露，按第 3 节发布新公钥/切换签名 key，并在必要时提前撤下受损 `kid`；提前撤下会
   主动使该 key 签发的尚未过期 Access 失效。

Principal 由验证后的 JWT 声明构造，不按请求查询数据库。因此 User 禁用或权限变更通常在 Access
剩余 TTL 内最终生效；紧急禁用同时需要撤销 Refresh family，并对当前 Access `jti` 使用 denylist。
