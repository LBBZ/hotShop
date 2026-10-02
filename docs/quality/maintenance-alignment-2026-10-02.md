# 2026-10-02 验证环境与演示入口整理

[证据索引](evidence-index.md) · [CI 说明](ci.md) · [容器手册](../runbooks/container-environment.md)

受测代码为 `cb7c06b23fd3b237918ee375ecf8643938b77212`。执行检查时改动尚未提交，随后原样提交；本报告与待办更新不改变运行代码。日期采用 Asia/Shanghai。

## 修正范围

- 完整工作流的独立 Qdrant 从 1.15.4 对齐到应用 Compose 与 `.env.example` 已使用的 1.19.1，并保留 SHA-256 镜像固定。
- 新增 CI 策略检查，拒绝默认版本、示例覆盖值与完整工作流之间的版本偏移，也拒绝完整测试镜像缺少 digest。新增 6 个回归用例，覆盖旧版本残留、示例偏移、缺少配置或门禁等情况。
- 容器手册、部署帮助与演示 Compose 提示统一使用公开入口 `script/demo.ps1`；保留旧脚本、项目名前缀和配置兼容性。容器手册的当前验收链接更新到本轮产品验收。

镜像来源为 [Qdrant 1.19.1 官方发布](https://github.com/qdrant/qdrant/releases/tag/v1.19.1)，镜像索引通过 `docker buildx imagetools inspect qdrant/qdrant:v1.19.1` 核验。本轮固定值：

```text
qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10
```

## 验证结果

| 检查             | 结果与边界                                                                                                                                       |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| CI 脚本测试      | `python -m unittest discover -s script/ci/tests -v`，59 项通过；使用带 Git 与 POSIX sh 的 Python 3.12.11 Bookworm 容器，只读挂载仓库             |
| 一致性检查       | `python script/ci/check_ci_policy.py .`；先确认旧 1.15.4 配置失败，再修改镜像固定值，检查通过                                                    |
| Agent pytest     | 从当前 `agent/Dockerfile` 的 `test` 阶段构建 Python 3.12.14 镜像；295 项通过、7 项跳过，耗时 51.34 秒，其中 Qdrant/API 集成文件的 9 项用例均执行 |
| 完整 Agent 评测  | `python -m hotshop_agent.eval_runner --suite full --output /reports/full-eval.json`，29/29 通过，全部类别达到既有阈值                            |
| 工作流与 Compose | 固定 actionlint 镜像校验通过；默认 Compose 与演示 Compose 合并配置解析通过                                                                       |
| 文档与差异       | 本地链接、锚点与 `git diff --check` 通过                                                                                                         |

Agent 通过独立内部 Docker 网络访问固定镜像的真实 Qdrant，使用全新数据卷；pytest 与 eval 串行执行。测试覆盖索引重建、幂等重建、alias 原子切换、文档删除、模型与维度变更、过滤、引用和不可用路径。执行命令与完整工作流对应的 pytest / eval 步骤一致，pytest 显式指定 `-p pytest_asyncio.plugin -p no:cacheprovider`，并设置 `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1`。

7 项跳过分别为 6 项需要 `AGENT_CONTAINER_IMAGE` 的容器安全测试，以及 1 项需要 `AGENT_TEST_REDIS_URL` 的真实 Redis 测试。此轮范围是 Qdrant 版本对齐，没有执行这些独立环境矩阵。模型保持 FakeModel，向量保持 deterministic；结果不代表付费模型在线效果。

第一次运行 CI 脚本测试使用了不含 Git 的 Agent runtime 镜像，导致 3 项临时 Git 仓库测试报 `FileNotFoundError: git`。改用包含所需工具的 Python Bookworm 镜像后，59 项全部通过，没有为通过测试修改断言。

本机原始日志与 JUnit/eval 输出在忽略目录 `.local/verification/maintenance-alignment/`，不随仓库分发。测试结束后按本轮资源标签核对并删除了专用 Qdrant 容器、网络和数据卷。已有演示项目未迁移或重新初始化。托管 CI 的结果应查看本次推送提交对应的 Actions 运行。
