# 参与 HotShop 开发

先用[演示指南](docs/delivery/demo.md)运行项目，再按修改范围阅读[当前架构](docs/architecture/current-state.md)、[API 契约](docs/api/api-contract.md)和对应模块文档。历史任务计划用于了解背景；当前工作以现有代码、契约和本次需求为准。

## 工具链

| 范围     | 要求与来源                                                                                   |
| -------- | -------------------------------------------------------------------------------------------- |
| Java     | JDK 21；使用仓库 Maven Wrapper（3.9.16）                                                     |
| Web      | Node.js `^22.13.0 \|\| >=24.0.0`、pnpm 10.15.0；以 `web/package.json` 为准                   |
| Agent    | Python 3.12；运行与开发依赖分别锁在 `agent/requirements.lock`、`agent/requirements-dev.lock` |
| 集成环境 | Docker、Compose 2.24.4+；跨平台脚本使用 PowerShell 7+                                        |

Java Testcontainers 测试需要可访问的 Docker daemon。若只查看文档，Node.js 即可运行文档检查。不要用本机已有依赖的版本替代仓库声明的版本；依赖有变更时同步更新锁文件。

## 常用检查

所有命令从仓库根目录开始，Web 命令块例外。

```powershell
node script/check-docs.mjs
node --test script/tests/check-docs.test.mjs
python -m unittest discover -s script/ci/tests -v
python script/ci/check_ci_policy.py .
```

CI 脚本测试包含 POSIX shell 探针；Windows 上使用 Git Bash 提供的 `sh` 或 Linux 容器运行完整测试。

```powershell
# Java：单元测试和真实基础设施集成测试
./mvnw.cmd -B -ntp verify

# 只修改共享 Java 模块时，可先运行相关 reactor
./mvnw.cmd -B -ntp -pl common -am verify
```

Linux/macOS 使用 `./mvnw`。保留本地 `target/` 中仍需复核的证据时不要先执行 `clean`；CI 在全新 checkout 上执行 `clean verify`。

```powershell
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm api:check:admin
pnpm api:check:agent
pnpm exec playwright install chromium
pnpm exec playwright test e2e/smoke.spec.ts e2e/storefront.spec.ts
```

`pnpm check` 包含格式、lint、类型检查、单元测试、构建和 public/user/admin/Agent 四组客户端漂移检查；OpenAPI Generator 需要 Java 运行时，可复用项目的 JDK 21。admin、Agent 的独立命令用于定向检查。上面的 Playwright 套件使用模拟接口验证桌面和移动页面，真实服务验证见 [CI 说明](docs/quality/ci.md)。

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
