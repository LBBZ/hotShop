# 2026-10-02 桌面与手机产品验收

[证据索引](evidence-index.md) · [演示手册](../runbooks/container-environment.md) · [Web 使用说明](../../web/README.md)

本轮受测代码为 `beba232591a577dbcab0c4e4a1df3b78455f6dcd`。测试执行时这些改动尚未提交，随后原样提交；本报告不包含新的运行时代码。日期采用 Asia/Shanghai。

## 环境与复现

使用独立 Compose 项目 `hotshop-task21-accept1002`，从源码构建 Java、Python Agent 和 Web，初始化独立数据库、密钥与演示数据。入口为本机 `http://127.0.0.1:18082`。已有演示项目未改动。服务包括 MySQL、Redis、RabbitMQ、Qdrant、Java 交易服务、Agent 与 Nginx 中的 Web 生产产物。

从仓库根目录启动：

[本地运行入口](../delivery/demo.md)

界面修正后重新构建 `web-demo`，再从 `web/` 执行最终验收：

```powershell
$env:HOTSHOP_DELIVERY_URL = 'http://127.0.0.1:18082'
$env:HOTSHOP_E2E_NETWORK_LOG = '1'
pnpm test:delivery
```

测试会注册账号、创建订单、调整演示库存，只适用于允许修改数据的独立演示环境。实际执行使用本机已安装的 Playwright 1.57.0，直接调用对应 CLI，等价于上述脚本。最终 Web 镜像为 `sha256:629de8292dd29837e70ce94b1743ef4ce2517f5544df13aac9cdb5775294949b`，构建时通过 frozen lockfile 安装声明依赖。

## 结果与修正

| 检查               | 结果                                                                                                                                            |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 真实服务浏览器验收 | 桌面 Chromium 与 Pixel 7 各 7 项，共 14 项通过；覆盖购买、Mock 支付、预约、Agent 确认购买、后台库存冲突、越权动作拒绝、审计读取及中英文检索引用 |
| 真实限流恢复       | 单独复验管理员登录收到 HTTP 429 后遵守 `Retry-After: 149`，等待后登录与审计检查成功，1 项通过                                                   |
| Agent 前端单测     | `agent-workspace` 与 `agent-stream` 共 17 项通过                                                                                                |
| Web 静态检查与构建 | 全量 ESLint、TypeScript 与 Prettier 检查通过；容器内生产构建通过                                                                                |
| 界面复核           | 检查 390px 手机与 1440px 桌面的购物助手；手机无页面横向溢出，确认购买与订单入口可见；更新文档助手截图                                           |
| 仓库展示           | 更新 GitHub About 与 Topics；制作 1280 × 640、约 153 KB 的分享预览图，并保存可编辑源文件与生成脚本。图片文件入库不代表 GitHub 仓库设置已上传    |

购物助手现在用中文描述处理进度，订单完成后明确引导用户查看订单与模拟支付；后台仍保留事件名称用于排查。购买确认说明明确提示会重新检查实时库存与价格，创建订单后仍需支付。交易、认证和模型行为未改动。

首次手机验收有 1 项因管理员登录限流失败：桌面与手机共用演示管理员，连续执行消耗了默认的每 300 秒 6 次身份额度。测试现仅在真实 HTTP 429 时读取有效的 `Retry-After`、扩展本用例超时并重试一次；未修改服务端限流。随后最终 14 项通过，另用真实限流响应确认恢复路径可用。库存测试中的 HTTP 409 是验证过期库存版本冲突的预期结果。

原始本机日志保存在忽略目录 `.local/verification/product-acceptance/`，包括 `demo-start.log`、`web-rebuild.log`、`delivery-mobile.log`、`delivery-final.log` 与 `admin-retry-verification.log`。这些本机文件不随仓库分发，本报告记录的是当轮观察结果；持续集成结论应以对应提交的具体 Actions 运行记录为准。

## 结论边界

浏览器访问真实容器服务，模型使用默认 FakeModel、支付使用 Mock、向量使用 deterministic embedding。验收没有调用付费模型、实际扣款，也不证明生产模型效果、并发性能或基础设施故障恢复能力。

本机静态检查与单测使用已有 Node 22.20.0、TypeScript 5.9.2、Vite 7.3.5、Vitest 3.2.6、ESLint 9.34.0、Prettier 3.6.2；其中部分与 manifest 声明版本不同。容器生产构建使用锁定依赖，CI 也会重新安装锁定依赖。未在本机重跑完整 Java reactor、基础设施故障矩阵或全仓库安全审计。
