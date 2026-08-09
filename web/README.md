# HotShop Web

一个 React + TypeScript + Vite 单应用，包含匿名、User 和 Administrator
三套路由壳。User 与 Administrator 的 Access Token、refresh single-flight
和生成客户端完全独立。

## 安全边界

- Access Token 只存在于两个独立 Zustand 内存 store，不启用任何持久化 middleware。
- 不把 Access Token 写入 `localStorage`、`sessionStorage`、IndexedDB 或 Cookie。
- Refresh Cookie 由浏览器以 `credentials: include` 发送；前端只读取对应域的非
  HttpOnly CSRF Cookie，并发送 `X-CSRF-Token`。
- 路由守卫、菜单和按钮隐藏只改善体验。后端 User/Admin API 始终是最终授权者。
- 同一身份域的并发 401 共用一个 refresh Promise；refresh 失败会一次性清空整个
  Access Session。两个身份域没有共享 refresh 状态。

## OpenAPI 客户端

输入是仓库中只读的 `docs/api/openapi-baseline/{public,user,admin}.json`，生成器固定为
OpenAPI Generator 7.14.0。`src/api/generated/*` 中的 TypeScript 文件禁止手工修改。

```bash
pnpm api:generate
pnpm api:check
```

运行时 API origin 来自 `VITE_API_BASE_URL`；缺省使用同源地址。生成器的 localhost
fallback 不作为运行配置。

## 本地命令

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm format:check
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm test:e2e
pnpm api:check
```

## TASK-13 real journey

`e2e/smoke.spec.ts` remains a fast mocked shell smoke. `e2e/user-transaction-real.spec.ts` is the real Compose gate: it does not use `page.route`, call the provider callback, or contain the callback HMAC secret. Load the deterministic UTF-8 seed into a fresh isolated Compose project. Global setup then signs in as the local seed Administrator and uses the audited activity-load API before both Playwright projects run.

```bash
HOTSHOP_REAL_COMPOSE=1 \
HOTSHOP_PORTAL_URL=http://127.0.0.1:18080 \
HOTSHOP_ADMIN_URL=http://127.0.0.1:18088 \
HOTSHOP_E2E_COMPOSE_PROJECT=hotshop-task13-reconcile \
HOTSHOP_E2E_SHORT_ACCESS=1 \
HOTSHOP_E2E_SHORT_TIMEOUT=1 \
pnpm exec playwright test e2e/user-transaction-real.spec.ts
```

The Compose services must set a 60-second User access TTL and short order/payment timeouts for the two explicit expiry/race proofs. Payment scenarios are initiated only through the User Mock Checkout API; delivery then follows Outbox → RabbitMQ → Task → signed callback → durable timeline → SSE.

## Docker

宿主机只需要 Docker。镜像固定 Playwright Chromium 版本，并内置 Node、pnpm 与 Java
运行时，可执行全部 Node、OpenAPI 和 Playwright 工具：

```bash
docker compose -f web/compose.yaml build
docker compose -f web/compose.yaml run --rm web pnpm check
docker compose -f web/compose.yaml run --rm web pnpm test:e2e
```

快速 smoke 使用路由 mock；TASK-13 real journey 必须连接上面的隔离 Compose 后端。
