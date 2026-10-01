# 参与 HotShop 开发

先用[演示指南](docs/delivery/demo.md)了解完整购物流程，再按修改范围选择开发与验证方式。

## 获取项目与选择入口

需要 Git。首次获取项目：

```powershell
git clone https://github.com/LBBZ/hotShop.git
cd hotShop
```

已经克隆时，直接进入现有仓库。以下文档分别说明各模块的启动方式、配置与边界：

| 修改范围            | 从哪里开始                                                                                                                                                                 |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 前端页面与交互      | [Web 开发指南](web/README.md)：安装依赖、启动 Vite、配置后端代理和运行页面测试                                                                                             |
| Java 交易与后台服务 | [当前架构](docs/architecture/current-state.md)了解模块职责，[容器指南](docs/runbooks/container-environment.md)配置依赖与服务，[API 契约](docs/api/api-contract.md)核对接口 |
| Python 购物助手     | [Agent 运行手册](docs/runbooks/agent-service.md)：本地启动、模型配置、密钥和测试；[Agent 工具边界](docs/architecture/agent-tools-and-confirmation.md)说明交易工具权限      |
| 文档与 CI           | [文档中心](docs/README.md)定位当前指南，[CI 说明](docs/quality/ci.md)了解自动化检查                                                                                        |

仅启动 Vite 不会启动后端；使用非默认端口时，按 Web 指南配置代理。完整容器演示入口为 `pwsh -NoProfile -File ./script/demo.ps1 -Action Start`，安装要求与操作步骤见[演示指南](docs/delivery/demo.md)。

## 工具链

| 范围     | 要求与来源                                                                                     |
| -------- | ---------------------------------------------------------------------------------------------- |
| 文档     | Git checkout、Node.js；CI 脚本检查还需要 Python                                                |
| Java     | JDK 21；使用仓库 Maven Wrapper（3.9.16）                                                       |
| Web      | Node.js `^22.13.0 \|\| >=24.0.0`、pnpm 10.15.0；`pnpm check` 还需要 Java 运行时，可使用 JDK 21 |
| Agent    | Python 3.12；运行与开发依赖分别锁在 `agent/requirements.lock`、`agent/requirements-dev.lock`   |
| 集成环境 | Docker、Compose 2.24.4+；跨平台脚本使用 PowerShell 7+                                          |

Java Testcontainers 测试需要可访问的 Docker daemon。文档检查通过 Git 枚举文件，因此应在已安装 Git 的仓库 checkout 中运行。精确版本以各模块清单和锁文件为准；依赖有变更时同步更新锁文件。

## 常用检查

按修改范围选择检查。每个命令块都从仓库根目录开始，Web 命令块会先进入 `web`。无需为只改文档而启动完整应用。

文档链接与标题锚点检查（Git、Node.js）：

```powershell
node script/check-docs.mjs
node --test script/tests/check-docs.test.mjs
```

CI 脚本检查（Python）：

```powershell
python -m unittest discover -s script/ci/tests -v
python script/ci/check_ci_policy.py .
```

CI 脚本测试包含 POSIX shell 探针；Windows 上建议在 Linux 容器运行完整测试。使用 Git Bash 时，需要同时确保 `sh` 与 `python3` 可执行，只有 `python` 命令并不足够。

```powershell
# Java：单元测试和真实基础设施集成测试
./mvnw.cmd -B -ntp verify

# 只修改共享 Java 模块时，可先运行相关 reactor
./mvnw.cmd -B -ntp -pl common -am verify
```

Linux/macOS 使用 `./mvnw`。保留本地 `target/` 中仍需复核的证据时不要先执行 `clean`；CI 在全新 checkout 上执行 `clean verify`。

Web 完整检查需要 Node.js、pnpm 和 Java 运行时；页面测试使用模拟接口，无需先启动后端：

```powershell
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm exec playwright install chromium
pnpm exec playwright test e2e/smoke.spec.ts e2e/storefront.spec.ts
```

`pnpm check` 包含格式、lint、类型检查、单元测试、构建和 public/user/admin/Agent 四组客户端漂移检查。`pnpm api:check:admin`、`pnpm api:check:agent` 可用于定向检查。上面的 Playwright 套件验证桌面和移动页面，真实服务验证见 [CI 说明](docs/quality/ci.md)。

Agent 可以直接复用固定测试镜像，避免本机 Python 与插件污染：

```powershell
docker build --target test -t hotshop-agent:local-test -f agent/Dockerfile agent
docker run --rm --network none --entrypoint python hotshop-agent:local-test -m ruff check .
docker run --rm --network none --entrypoint python hotshop-agent:local-test -m ruff format --check .
docker run --rm --network none --entrypoint python hotshop-agent:local-test -m mypy --no-incremental src tests
docker run --rm --network none -e AGENT_MODEL_PROVIDER=fake -e AGENT_EMBEDDING_PROVIDER=deterministic --entrypoint python hotshop-agent:local-test -m pytest -m 'not qdrant' -p pytest_asyncio.plugin -p no:cacheprovider
```

真实 Qdrant 集成测试和 full eval 需要独立的内部 Docker 网络，步骤见[Agent 运维](docs/runbooks/agent-service.md)与[完整工作流](.github/workflows/full-verification.yml)。默认测试使用 FakeModel / deterministic embedding，不依赖付费模型。

## 变更约定

- API 改动同步更新运行时 OpenAPI、基线和生成客户端。生成文件由脚本维护，不手工修补类型以绕过漂移检查。
- 数据库结构通过新的 Flyway 迁移演进，已应用的迁移保留原内容。
- 交易改动验证幂等、库存和异常恢复；Agent 改动验证身份、scope、资源归属和用户确认。
- 页面改动检查键盘操作、窄屏、加载/空/错误状态与 reduced motion。设计验证使用可复现的数据和清晰标注的示意素材。
- 文档的能力描述与验证结果分开。测试数量、性能和扫描结论绑定受测版本；历史报告保留失败、限制和日期。
- 演示凭据只用于隔离环境。真实 `.env`、私钥和令牌保留在忽略目录，不提交到版本库。

提交说明写清问题、改变后的行为和验证范围。分支合并后可删除没有独有提交的旧任务分支；仍有未合并工作的分支保留供审查。
