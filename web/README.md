# HotShop Web

HotShop 的 React 单应用：公开商城、个人购物空间、购物助手和管理员工作台。商品、订单、库存和支付状态来自后端 API；页面只展示服务端事实。本地支付为模拟流程，不产生真实扣款。

[项目说明](../README.md) · [当前架构](../docs/architecture/current-state.md) · [API 契约](../docs/api/api-contract.md)

## 技术与页面

当前声明版本为 React 19.3.0、TypeScript 6.0.3、Vite 8.3.1、Tailwind CSS 4.3.3、React Router 7.18.3。服务端查询使用 TanStack Query，独立身份状态使用 Zustand。精确依赖以 [package.json](package.json) 和 [pnpm-lock.yaml](pnpm-lock.yaml) 为准。

| 入口                                                                     | 内容                                                                            |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------- |
| `/`、`/products/:productId`                                              | 商品浏览、分类/搜索、商品详情、普通购买和秒杀入口                               |
| `/auth`                                                                  | 用户登录/注册                                                                   |
| `/user`、`/user/orders`                                                  | “我的购物空间”、订单列表和进度                                                  |
| `/user/orders/:orderId`、`/user/reservations/:activityId/:reservationNo` | 本人交易、模拟支付、持久 timeline 和 SSE                                        |
| `/user/agent`                                                            | 购物助手、连续对话、静态知识引用和需用户确认的购买草稿                          |
| `/admin/login`、`/admin`                                                 | 独立管理员身份、运营总览、商品/库存、活动、订单、异常、Outbox、审计与受限 Agent |

视觉系统使用灰蓝背景、深蓝文字、珊瑚色操作提示与青色成功状态。展示标题、正文与紧凑数据分别使用 Barlow Condensed、Manrope、IBM Plex Mono；字体由项目依赖本地打包。响应式布局、键盘焦点、状态朗读与减少动效规则集中在 [styles.css](src/styles.css) 及组件中。

## 启动

体验包含后端、Agent 和演示数据的完整应用，优先使用根目录的 [隔离演示入口](../docs/runbooks/container-environment.md)。仅运行前端不会自动启动这些服务。

本机开发需要 Node 22.13+（22.x）或 Node 24+、Corepack/pnpm 10.15.0。在 `web` 目录执行：

```powershell
corepack pnpm@10.15.0 install --frozen-lockfile
corepack pnpm@10.15.0 dev
```

默认打开 `http://127.0.0.1:4173`。Vite 转发规则如下，非默认后端端口需在启动 Vite 前设置相应环境变量：

| 浏览器路径                       | 默认目标                | 环境变量             |
| -------------------------------- | ----------------------- | -------------------- |
| `/api`、`/provider-callbacks`    | `http://127.0.0.1:8080` | `HOTSHOP_PORTAL_URL` |
| `/admin/api`                     | `http://127.0.0.1:8088` | `HOTSHOP_ADMIN_URL`  |
| `/agent-api`（转发时去掉该前缀） | `http://127.0.0.1:8090` | `HOTSHOP_AGENT_URL`  |

`VITE_API_BASE_URL` 是构建期 API origin 配置，缺省使用同源地址。完整演示的 Nginx 已配置同源代理；Vite `preview` 只适合检查构建产物，不能替代完整运行栈的代理配置。API client 中生成的 localhost fallback 不是部署配置。

请求 ID 与购买幂等键使用 `crypto.randomUUID()`，页面需要安全上下文：本机使用 `http://localhost` 或 `http://127.0.0.1`，远程环境使用 HTTPS。容器内浏览器测试使用其可达的 localhost 代理。

## 验证

在 `web` 目录运行：

```powershell
corepack pnpm@10.15.0 format:check
corepack pnpm@10.15.0 lint
corepack pnpm@10.15.0 typecheck
corepack pnpm@10.15.0 test
corepack pnpm@10.15.0 build
corepack pnpm@10.15.0 exec playwright install chromium
corepack pnpm@10.15.0 test:e2e
corepack pnpm@10.15.0 api:check
```

`check` 串联格式、lint、类型、单测、构建和客户端漂移检查；OpenAPI 生成/漂移检查还需 Java 运行时，可使用仓库的 Java 21 或下方 Docker 工具镜像。Vitest 最多使用两个 worker。

浏览器验证有三个明确入口：

| 范围                | 入口（从仓库根目录执行）                                                                      | 依赖                                                             |
| ------------------- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| 快速页面/交互回归   | `corepack pnpm@10.15.0 --dir web test:e2e`                                                    | Vite 自动启动；默认使用 mock，真实 Compose 规格未启用            |
| 真实交易与故障路径  | `pwsh -NoProfile -File script/verify-task19-e2e.ps1`                                          | Docker/Compose 和 PowerShell 7；脚本创建并清理独立后端与测试资源 |
| 构建后的 Nginx 应用 | `corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts` | 已启动完整演示，默认 `http://127.0.0.1:18080`                    |

真实交易测试通过 User Mock Checkout API 发起支付，经过 Outbox → RabbitMQ → Task → 签名回调 → timeline/SSE；不会以浏览器路由 mock 或测试侧直接回调代替交易链路。测试定义与既往结果见 [用户交易链路](../docs/architecture/user-transaction-journey.md) 和 [质量报告](../docs/quality)。

## 身份、查询和流式边界

- User 与 Administrator 使用独立 Access Token 内存 store、生成客户端和 refresh single-flight。Token 不写入 localStorage、sessionStorage、IndexedDB 或 Cookie。
- Refresh Cookie 通过 `credentials: include` 发送；前端读取本身份域的非 HttpOnly CSRF Cookie，并发送 `X-CSRF-Token`。后端验证身份、scope 与资源归属，路由和按钮只是交互控制。
- 私人查询 key 包含身份域与用户 ID；退出或换账号取消并移除旧查询。迟到的刷新或请求不能覆盖新身份。
- 普通购买与秒杀预约为一次购买意图复用同一幂等键。按钮成功不会直接将订单标为已支付。
- 交易 SSE 支持 `Last-Event-ID`、心跳超时、带抖动的重连与 `Retry-After`；断连或快照失败时每 10 秒兜底读取服务端事实，成功取得终态后停止轮询。
- 购物助手在当前页面内复用 session，最多保留 12 轮历史；开始新对话或切账号会重置。服务端以受限、不可信上下文提供历史，每次 run 最多调用一个工具。
- Agent 只有明确的 `done` 事件代表运行结束。截断/超时显示未完成；取消或离开页面中止读取并请求取消，旧事件不能污染后续运行。
- 购买草稿不会扣库存或下单。一次性确认值只存在于签发后立即消费的局部变量，不进入组件状态、存储、日志或模型。

## OpenAPI 客户端

输入为 `docs/api/openapi-baseline/{public,user,admin,agent}.json`，生成器固定为 OpenAPI Generator 7.14.0。[src/api/generated](src/api/generated) 内的生成文件禁止手改。

```powershell
corepack pnpm@10.15.0 api:generate
corepack pnpm@10.15.0 api:check
```

修改 API 时先从运行时导出并评审基线差异，再重新生成客户端；流程见 [API 契约](../docs/api/api-contract.md#7-openapi客户端与兼容门禁)。SSE 帧协议由独立解析器处理，不伪装为普通 OpenAPI JSON 响应。

## Docker 工具与运行镜像

从仓库根目录执行：

```powershell
docker compose -f web/compose.yaml build
docker compose -f web/compose.yaml run --rm web pnpm check
docker compose -f web/compose.yaml run --rm web pnpm test:e2e
```

`web/compose.yaml` 使用 `test` target，内置固定 Playwright Chromium、Node、pnpm 和 Java；`web/Dockerfile` 的 `runtime` target 使用 Nginx 提供静态产物和代理。Nginx 对带 hash 的 assets 使用一年 immutable 缓存并压缩文本资源，HTML 重新验证，API 缓存策略由后端控制。
