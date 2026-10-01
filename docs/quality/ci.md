# 持续集成与本地验证

[文档中心](../README.md) · [贡献指南](../../CONTRIBUTING.md) · [验证证据](evidence-index.md)

本页描述当前工作流定义，不代表最新提交已通过托管运行。实际结果见 [GitHub Actions](https://github.com/LBBZ/hotShop/actions)，历史运行与计数保留在[旧 CI 报告](ci-history-2026-09-16.md)。

## 快速工作流

[ci.yml](../../.github/workflows/ci.yml)在 Pull Request 和 `master` push 时执行。变更检测按 Java、Agent、Web、OpenAPI、Docker 和 CI 选择检查；根构建配置和未知路径采取完整检查。纯 Markdown 文档可以跳过不相关的构建，但仍运行文档、CI 与安全策略门禁。OpenAPI JSON 不按普通文档跳过。

| Job                             | 验证范围                                                                                                                 |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| Change detection                | 分类实际变更，生成选择结果                                                                                               |
| CI and security policy          | 文档链接与检查器测试、CI 策略测试、Compose 清理所有权探针、Prometheus 规则、actionlint、Gitleaks 和固定本地 Semgrep 规则 |
| Java 21 verify                  | Temurin 21、Maven Wrapper `clean verify`、Testcontainers、Surefire/Failsafe 与必需 JaCoCo 报告                           |
| Agent deterministic gate        | 固定 Python 测试镜像、Ruff、format、strict mypy、非 Qdrant pytest、定向 coverage 和 quick eval                           |
| Web quality and mocked smoke    | frozen pnpm 安装、格式/lint/类型、Vitest/coverage、构建、客户端漂移，以及桌面/移动 smoke 与 storefront 浏览器测试        |
| OpenAPI compatibility and drift | 导出实际接口、比对兼容性基线、检查生成客户端与工作树漂移                                                                 |
| Docker reproducible builds      | Compose 解析、Java/Agent/Web 镜像构建、运行身份检查，不推送镜像                                                          |
| Required CI gate                | 聚合被选 job；失败、取消或意外 skipped 都会阻止通过                                                                      |

快速浏览器测试模拟 API，验证页面状态与交互；真实服务旅程由完整工作流负责。

## 完整工作流

[full-verification.yml](../../.github/workflows/full-verification.yml)支持手动触发，每周六 18:17 UTC 定时运行。它包含完整 Java、真实 Qdrant pytest / full eval、Web 与 OpenAPI、隔离 Compose、真实桌面/移动 E2E、故障恢复矩阵，以及 Gitleaks / OSV / Trivy / Semgrep / ZAP 与镜像扫描，最终由 `Full verification gate` 聚合。

独立 Qdrant 测试镜像当前固定为 **1.15.4**（带 digest）；默认应用 Compose 是 **1.19.1**。二者的测试环境不同，不能把前者的结果表述为后者已通过验证。Qdrant 测试和 eval 串行使用独立内部网络，避免同时重建同一 alias。

两个工作流都使用 FakeModel 和 deterministic embedding。快速 Agent 以 `--network none` 运行；完整 Agent 测试只接入内部 Qdrant 网络，不注入付费模型凭据。Provider 契约使用 MockTransport；它不证明真实厂商在线联调结果。

## 本地复现

常用 Java、Web、Agent 命令见[贡献指南](../../CONTRIBUTING.md)。验证脚本的实际入口与参数以工作流为准；文件名中的任务编号只表示起源。运行包含资源清理的脚本时保留其独立项目名和所有权检查。

```powershell
# 不安装 Web / Python 依赖即可运行文档校验
node script/check-docs.mjs
node --test script/tests/check-docs.test.mjs

# CI 自身的测试；完整集合需要 POSIX sh
python -m unittest discover -s script/ci/tests -v
python script/ci/check_ci_policy.py .

# 验证默认 Compose 配置语法
docker compose --env-file .env.example config --quiet
```

文档检查覆盖现存 Git 文件和未忽略的新 Markdown、本地文件链接与标题/HTML 锚点，不请求远程 URL。结果写入 `target/docs-check/result.json`。可选 Mermaid 渲染需要已安装的 Web Playwright / Chromium 和显式指定的 Mermaid 工具目录：

```powershell
node script/check-docs.mjs --mermaid-tools <包含node_modules/mermaid的目录>
```

旧 `script/verify-task21-docs.mjs` 保留兼容入口，但已不再依赖硬编码历史提交或自动安装工具。

## 报告与门禁

报告名称包含 `run_id` 和 `run_attempt`。Java XML/JaCoCo、Agent JUnit/coverage/eval、Web coverage 和 OpenAPI 报告一般保留 7 天，失败浏览器证据保留 3 天，具体以各上传步骤为准。需要长期保留的证据应脱敏后绑定受测 SHA，不能依赖短期 artifact 永久可用。

建议 `master` 使用 `Required CI gate` 作为 required check。条件执行的各组件 job 不宜独立设为必需，否则正常的文档变更跳过会阻塞合并。定时/手动的完整门禁与 PR 快速门禁有不同用途。

Coverage 是已配置范围的测量值，不等于全项目验证：Agent 使用定向采样，Java 要求指定服务报告非空。GitHub 托管 Runner 的功能测试也不构成生产吞吐、延迟或容量结论。依赖自动更新仍需经过相同门禁。
