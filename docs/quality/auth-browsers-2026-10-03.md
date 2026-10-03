# 2026-10-03 跨浏览器登录恢复验收

[证据索引](evidence-index.md) · [结构化结果](auth-browsers-2026-10-03.json) · [运行入口](../../web/README.md#验证) · [认证手册](../runbooks/authentication-operations.md#41-多标签与轮换响应丢失)

本轮在真实认证后端与构建后的 Nginx 页面上验证了 20 项流程，全部通过，未跳过或自动重试。
运行代码基线为 `904221adcde2f05e557aa165de5ff4a8d31c392b`，测试实现提交为
`6e4ccf4c60bb90d6abbe29fd43febfb6e69601e2`。浏览器执行时新增测试尚未提交；测试规格与专用配置随后原样提交。
执行后仅补充报告目录的递归创建、默认套件注释及文档，没有修改产品运行代码或认证协议。

## 验证环境

| 项目 | 本轮取值 |
| --- | --- |
| 开始时间 | 2026-10-03 20:29:54，Asia/Shanghai |
| 总耗时 | 146.86 秒，包含每项 5 秒的登录限流间隔 |
| 演示项目 | `hotshop-task21-accept1002`，`http://127.0.0.1:18082` |
| Web 镜像 | `sha256:5d700f6d68b0dbd37f8eedfdfd5ebe7bb590ccef08a94bc433d04ad66b8b4534` |
| 测试宿主 | Windows x64，Node 22.20.0，pnpm 10.15.0，Playwright 1.57.0 |
| Chromium | 143.0.7499.4 |
| Firefox | 144.0.2 |
| WebKit / 手机尺寸 WebKit | 26.0；手机项目使用 iPhone 13 模拟参数 |

各项目串行执行，只注册独立测试账号并创建登录会话，不修改商品、库存或订单。
页面与 API 同源，本机地址提供 Web Locks 所需的受信上下文。Web 镜像按仓库锁文件构建；没有使用本机 Vite 开发服务器替代受测页面。

## 20 项真实流程

| 场景及断言 | Chromium | Firefox | WebKit | 手机尺寸 WebKit |
| --- | --- | --- | --- | --- |
| 两标签同时恢复登录，各轮换成功；重新加载后仍处于本人购物空间 | 通过 | 通过 | 通过 | 通过 |
| 关闭正在等锁的标签；释放锁后只有存活页面发出一次轮换 | 通过 | 通过 | 通过 | 通过 |
| 关闭持锁页面；等待中的页面取得锁并显示订单列表 | 通过 | 通过 | 通过 | 通过 |
| 服务端轮换成功但响应丢失；只尝试一次，重新登录后回到订单页 | 通过 | 通过 | 通过 | 通过 |
| 轮换已提交但新 Cookie 未送达时关闭页面；下一标签得到 401，重新登录及再次加载成功 | 通过 | 通过 | 通过 | 通过 |

测试使用浏览器实际的 Web Locks 持锁、排队和释放行为。双标签竞争人为延迟 refresh 的发出，以重叠两个页面的恢复过程，响应仍来自真实服务。
响应丢失测试使用独立 HTTP Cookie 上下文向真实后端提交轮换，断言成功后中止浏览器请求；新 `Set-Cookie` 不会写入浏览器上下文。
这一区分确保测试实际覆盖“服务端已轮换、浏览器仍持有旧 Cookie”，而不是只隐藏成功响应的正文。

客户端不自动重试结果未知的 refresh。下一标签提交旧 Cookie 后返回 401，重新登录再建立可用会话；本轮保持原有 CSRF、登录限流与 family 重放检测。
这些观察支持现有恢复流程在本轮环境中工作正常，本轮未据此添加运行时修复。

## 复现与检查

先按[容器手册](../runbooks/container-environment.md)启动可变更的独立演示，再从仓库根目录执行：

```powershell
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web exec playwright install chromium firefox webkit
node script/verify-auth-browsers.mjs hotshop-task21-demo0001 http://127.0.0.1:18080
```

替换项目名与端口。入口要求本工作树中存在该项目的演示配置，并核对运行中的 Web 容器及映射端口。
浏览器日志、JSON 与运行摘要保存在忽略目录 `.local/verification/auth-browsers-*`；禁用 trace、截图和视频，诊断注解只记录浏览器版本及是否存在 Cookie header，不记录凭据值。
测试创建的账号与登录记录保留在选定演示的数据库中，随该隔离演示的数据生命周期清理。

| 附加检查 | 本轮结果 |
| --- | --- |
| 冻结锁文件安装 | 通过；校正本机旧 `node_modules`，锁文件未变更 |
| Web 格式、ESLint、TypeScript 与构建 | 通过；Vite 8.3.1、React 19.3.0、TypeScript 6.0.3 |
| 默认浏览器套件收集 | 58 项、5 个文件；新真实认证套件未混入，本轮只收集该默认套件，未执行它 |
| 验收入口语法与 Git 差异空白 | 通过 |
| 文档本地链接与锚点 | 通过 |

本机格式检查使用 `prettier --end-of-line auto --check .` 兼容现有 Windows 换行。
类型检查包含在 `pnpm build` 的 `tsc -b` 阶段，随后完成 Vite 生产构建。
完整 CI 仍按自身变更范围运行；四组真实浏览器需显式调用此独立入口，默认 CI 不会自动部署它们所需的演示环境。

## 保留的失败记录

第一轮在 20:26:55 开始，14 项通过、6 项失败，耗时 45.85 秒。

1. 两项 WebKit 故障注入在独立请求端返回 403：该浏览器拦截请求的 `allHeaders()` 未提供 Cookie header，转发缺少浏览器 Cookie。夹具改为从浏览器复制 Cookie 到独立内存上下文，继续阻断响应 Cookie 回写；没有更改或绕过服务端 CSRF 校验。
2. 随后的四项手机项目在账号登录准备阶段返回 429。串行用例增加 5 秒间隔后通过，原有每 IP 每分钟 20 次登录限制保留，没有通过重试或调高限额掩盖失败。
3. 收尾时发现本机依赖仍有旧 Vite / React / TypeScript 版本。冻结安装后重新完成格式、lint、类型和构建检查。实际受测 Web 来自按当前锁文件构建的容器，Playwright 在安装前后均为 1.57.0，因此这一发现不改变上述浏览器结果。

首轮和最终原始证据分别位于本机忽略目录
`.local/verification/auth-browsers-20261003122653853/` 与
`.local/verification/auth-browsers-20261003122952938/`。
公开 JSON 只提取版本、场景、状态、耗时和统计值，保留失败计数，不包含原始 Cookie、Token 或完整浏览器日志。

## 覆盖边界

本轮覆盖 User 身份、指定版本和本机同源环境。Windows 上的 Playwright WebKit 及 iPhone 13 参数模拟不等于 macOS Safari 或 iOS 真机；Administrator 身份的同类矩阵、跨源部署、无 Web Locks 环境和浏览器进程被强制终止均未在本轮验证。
后续范围继续记录在[当前待办](../delivery/next-iteration.md)，不以这 20 项结果推断所有浏览器或部署环境均已覆盖。
