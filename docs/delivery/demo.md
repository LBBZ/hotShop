# 本地演示指南

[文档中心](../README.md) · [容器配置](../runbooks/container-environment.md) · [开发指南](../../CONTRIBUTING.md)

本指南使用仓库现有的隔离演示脚本，启动最终 Nginx Web、Java 交易服务、Agent 和基础设施。默认 FakeModel / deterministic embedding，无需模型账号。支付是模拟流程。

## 1. 启动

需要 PowerShell 7+、Docker Engine / Docker Desktop 的 Linux 容器和 Docker Compose 2.24.4+。运行 `docker info` 和 `docker compose version` 确认 Docker 可用，然后在仓库根目录执行：

```powershell
pwsh -NoProfile -File ./script/task21-demo.ps1 -Action Start
```

默认入口为 **http://127.0.0.1:18080**。端口已占用时，在首次启动时追加 `-WebPort 18081`。脚本会打印形如 `hotshop-task21-xxxxxxxxxxxx` 的项目名，后续操作使用该名称。

首次构建需要联网下载镜像和依赖。脚本创建随机数据库/缓存/消息凭据与认证密钥，并存放在忽略目录 `.local/keys/<项目名>/`；等待数据库迁移成功和入口就绪后，初始化商品、三个限时活动与知识索引。活动有效期从初始化起约一天。已有项目、资源或同名镜像会触发拒绝，避免覆盖其他演示数据。

## 2. 体验购物流程

| 步骤     | 页面与操作                                            | 预期观察                                         |
| -------- | ----------------------------------------------------- | ------------------------------------------------ |
| 发现     | 首页「发现好物」，搜索或切换品类，进入商品详情        | 商品价格、库存、状态和购买入口；插画标为品类示意 |
| 登录     | 注册一个演示用户，再登录                              | 个人工作区、本人订单与预约入口                   |
| 普通购买 | 商品详情确认数量并下单                                | 新建待支付订单；重复请求受幂等约束               |
| 限时活动 | 在首页限时活动卡片提交预约                            | 先显示已接受预约，再显示异步订单状态             |
| 模拟支付 | 在订单页面发起支付，选择演示结果                      | 观察支付与订单终态；刷新后以服务端状态为准       |
| AI 购物  | 「AI 帮我选」或个人工作区「购物助手」，查询、比较商品 | 流式回答、工具结果、购买草稿；确认草稿才会建单   |
| 知识问答 | 询问知识库覆盖的活动规则、FAQ 或售后政策              | 有命中时展示来源；未命中或检索失败时明确提示     |

每位用户在每个活动中只能持有一条有效预约，超时或补偿后可以释放占位。重复预约、库存不足、活动结束都是可观察的正常业务状态。演示数据会随购买改变，不要反复执行 seed 来恢复库存；需要全新数据时启动一个新项目。

## 3. 体验后台

打开 **http://127.0.0.1:18080/admin/login**，若更换过端口则相应替换。

隔离 seed 中的管理员为 `task13-admin`，密码为 `Task13Admin!2026`。这是仓库公开的演示账号，只用于该隔离环境。

后台包含交易总览、商品、活动、订单、异常、Outbox、审计和 Agent 页面。库存调整需要填写变更量与原因；元数据编辑不直接覆盖库存。人工重放和运营操作会记录审计信息。管理员登录与普通用户登录使用独立的凭据边界。

## 4. 保留数据的日常操作

替换以下示例项目名：

```powershell
pwsh -NoProfile -File ./script/task21-demo.ps1 -Action Status -ProjectName hotshop-task21-xxxxxxxxxxxx
pwsh -NoProfile -File ./script/task21-demo.ps1 -Action Stop -ProjectName hotshop-task21-xxxxxxxxxxxx
pwsh -NoProfile -File ./script/task21-demo.ps1 -Action Restart -ProjectName hotshop-task21-xxxxxxxxxxxx
```

`Stop` 只停止容器，保留卷、配置和密钥。`Restart` 使用 Compose `start` 启动已有容器，不重建镜像、不重新初始化数据、不延长活动，也不会重新执行完整就绪探针。稍候再访问入口，必要时用 `Status` 查看健康状态。

启动失败会保留现场。用实际项目名检查服务和日志：

```powershell
docker ps -a --filter label=com.docker.compose.project=hotshop-task21-xxxxxxxxxxxx
docker logs <上一步找到的容器名称>
```

常见原因包括端口占用、Docker 未启动、镜像下载失败和迁移失败。密钥配置不一致时不要复用别的项目的卷或 `.env.demo`。手动管理资源的方法见[容器指南](../runbooks/container-environment.md)。

## 5. 演示与测试的区别

本指南供交互体验；自动化验证应使用 [CI 工作流](../quality/ci.md)与相应隔离脚本，从工作流定位实际入口和参数。不要将历史报告中的通过数量当作当前运行结果。

默认 AI 的回答质量受 FakeModel 和确定性检索限制；Agent 未完成运行不跨进程恢复；支付不涉及真实资金。历史验收及容量边界见[证据索引](../quality/evidence-index.md)。
