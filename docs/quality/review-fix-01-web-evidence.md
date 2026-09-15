# IR-01 前端回归证据

主 agent 在 `D:/Codex/Projects/hotShop-review-integration` 执行。商品编辑相关源码在红测时仍为基线 `a3e7a6c`（此前仅合入互不相关的 Agent 修复）。Node 22.20.0、`corepack pnpm@10.15.0`；独立 worktree 安装冻结 lockfile 的依赖，无全局工具版本修改。

## 先失败、再修复

```powershell
cd D:/Codex/Projects/hotShop-review-integration/web
corepack pnpm@10.15.0 install --frozen-lockfile
corepack pnpm@10.15.0 test src/pages/admin-products-page.test.tsx
```

先只新增 `a stale name-only form never sends its cached stock`，打开库存 100 的编辑器，模拟另一个请求将当前库存变为 99，修改名称并保存。基线结果 **1 failed**：请求仍含 `stock: 100`，`not.toHaveProperty("stock")` 失败（2026-09-15 22:21:06 +08:00）。这是页面行为回归，HTTP/数据库并发正确性由独立真实容器测试覆盖，不将模拟 API 写入解释为真实 MySQL 证据。

首次试跑因“变更原因”标签包含说明文字而定位失败；改用匹配该标签的正则后得到上述业务失败，未修改业务源码或放宽库存断言。

## 修复

普通资料编辑不显示可修改库存字段，页面和 API 封装只发送资料白名单。新增仍有初始库存。独立库存调整表单发送整数 `delta`、字符串 `expectedVersion`、原因；显示调整后的预期数量。发生 `STOCK_ADJUSTMENT_CONFLICT` 时说明本次未生效，禁止再次提交直到读取最新库存/版本，由管理员核对数量后显式重提。版本按 API 契约使用字符串传递，不作数值转换；当前数据库 version 类型仍为 INT。

新增回归覆盖：旧表单只改名称、独立调整及冲突刷新重提（使用接近 INT 上限的版本）、零调整和减至负数的客户端拒绝。

修复后同一测试命令 **3 passed**（22:23:14 +08:00，3.51 秒）；`corepack pnpm@10.15.0 typecheck` 退出 0。完整集成 HEAD 的前端门禁及运行时 OpenAPI/生成客户端验证由统一报告记录。原始本地日志位于集成 worktree 的 `target/review-fixes/web-red.log`、`web-green.log`。
