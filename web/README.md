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

以下命令在 `web` 目录的 Bash 终端执行，Node22+、pnpm10.15.0。完整本地环境优先使用
[根README](../README.md) 的PowerShell7隔离启动入口；Docker运行镜像无需宿主Node。

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

上述是历史手工接入示例，需要自行匹配真实端口与预置数据；当前完整入口为根目录
`pwsh -NoProfile -File script/verify-task19-e2e.ps1`。其中User TTL=60s、普通订单timeout=10s，
秒杀timeout保持默认；真实模式mobile项目只选择Agent/security规格。Payment scenarios are initiated
only through the User Mock Checkout API; delivery follows Outbox → RabbitMQ → Task → signed callback → durable timeline → SSE.

TASK-21新增 `playwright.delivery.config.ts`，直接验证已启动的Nginx运行镜像，默认18080，无Vite或route mock。
从仓库根目录运行 `corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts`。
验证范围和实际结果见[TASK-21](../docs/quality/task-21-delivery.md)。

## Docker

宿主机只需要 Docker。镜像固定 Playwright Chromium 版本，并内置 Node、pnpm 与 Java
运行时，可执行全部 Node、OpenAPI 和 Playwright 工具：

```bash
docker compose -f web/compose.yaml build
docker compose -f web/compose.yaml run --rm web pnpm check
docker compose -f web/compose.yaml run --rm web pnpm test:e2e
```

快速 smoke 使用路由 mock；TASK-13 real journey 必须连接上面的隔离 Compose 后端。
