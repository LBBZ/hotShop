# IR-02 修复与分支验证证据

- 日期：2026-09-15；分支 `review-fix-02-agent-recovery`。
- 同一基线：`a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`。
- 独立目录：`D:/Codex/Projects/hotShop-review-02`。
- 本文是功能分支证据；集成后验证与最终提交 SHA 由统一修复报告记录。

## 根因与设计

旧实现以共享布尔标志表示 HALF_OPEN 探测资格，取消与生成器关闭没有释放路径；旧请求也可无条件修改新熔断周期。

每次 provider 尝试先获得 `CircuitAdmission`。HALF_OPEN 资格归该对象的身份所有；成功、失败只能在 generation 相符且仍拥有探测资格时修改状态。打开熔断与成功探测关闭熔断均推进 generation，旧请求完成不能覆盖新周期。`finally` 只释放自己的 admission，取消与 `GeneratorExit` 不计模型失败。每次重试重新检查熔断状态，不能用旧准入绕过已打开的熔断器。

显式关闭具备 `aclose` 的 provider iterator，使客户端关闭流时及时释放 provider。若清理也失败，保留原始取消、关闭、超时或 provider 异常；没有原始异常时清理异常计失败并传播。所有路径仍通过原有 limiter 上下文释放额度。没有修改取消接口、API、数据库、OpenAPI 或前端。

## 修复前真实失败

### 原附件诊断（退出 0 仅代表复现旧 bug）

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test /review/docs/quality/review-2026-09-15/probe_circuit_cancel.py
```

输出：`after_cancel: half_open True 0`；随后三次均为 `CircuitOpenError model circuit is probing`。未将该退出码当成修复通过。

### 先写正式测试、再改实现

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-02,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test -m pytest -o addopts= -p no:cacheprovider -p pytest_asyncio.plugin tests/test_circuit_lifecycle.py -k 'cancelled_half or closed_half' --tb=short -q
```

基线实现结果：`2 failed, 3 deselected in 0.26s`（当时测试文件尚未补充其余场景）。

- `test_cancelled_half_open_probe_releases_ownership`：`assert not breaker.half_open_in_flight` 失败，实际 True。
- `test_closed_half_open_generator_releases_provider_and_probe`：`assert provider.closed.is_set()` 失败，provider 未同步关闭。

## 正式回归与修复后结果

`agent/tests/test_circuit_lifecycle.py` 新增 17 个参数展开后的测试：

- HALF_OPEN 用户取消，保留 `CancelledError`，拒绝同时探测，释放额度后可再次成功探测。
- HALF_OPEN 生成器 `aclose`，同步关闭 provider，并可再次探测。
- 成功、临时失败、永久失败、事件循环触发超时、意外异常及后续恢复。
- 已在 CLOSED 准入的旧请求，在新 HALF_OPEN 周期成功、异常或取消均不能改变新的 probe。
- 同周期被释放的旧 probe 不能释放或完成替代 probe；跨周期旧 probe 不能修改新周期。
- 重试必须重新准入，不能绕过已经打开的熔断器。
- 自定义 AsyncIterator 的 `aclose` 抛错：取消保留原始取消消息、关闭保留 GeneratorExit、超时仍转为 ModelTimeoutError、原始 provider 异常保留、普通成功流的清理失败仍计为失败。

事件同步使用 `asyncio.Event`；熔断时钟替换为局部可控时钟，不修改事件循环时钟；超时用 0 秒触发下一事件循环轮次，不依赖长时间 sleep。全部可控本地 provider，不调用付费模型。

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-02,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test -m pytest -o addopts= -p no:cacheprovider -p pytest_asyncio.plugin -m 'not qdrant' --tb=short -q
```

最终源码结果：**267 passed, 6 skipped, 8 deselected in 30.53s**，退出 0。6 项容器权限/运行时测试未提供镜像配置而跳过，8 项真实 Qdrant 测试排除；不声称通过这些边界。最小独立复验可在同一命令中将 `-m 'not qdrant'` 替换为 `tests/test_circuit_lifecycle.py tests/test_reliability.py tests/test_cancellation.py`；集成验收时挂载集成 worktree。

同样的只读挂载环境执行：

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-02,target=/review,readonly -w /review/agent --entrypoint python hotshop-agent:task20-test -m ruff check --no-cache .
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-02,target=/review,readonly -w /review/agent --entrypoint python hotshop-agent:task20-test -m ruff format --check --no-cache .
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop-review-02,target=/review,readonly -w /review/agent -e PYTHONPATH=/review/agent/src -e PYTHONDONTWRITEBYTECODE=1 --entrypoint python hotshop-agent:task20-test -m mypy --no-incremental --cache-dir=/tmp/hotshop-mypy src tests
```

- Ruff check：`All checks passed!`。
- 严格 mypy：`Success: no issues found in 54 source files`。
- 全 Agent format：退出 1，`Would reformat: tests/test_registry.py`，`1 file would be reformatted, 53 files already formatted`。
- 两个变更 Python 文件单独 `ruff format --check --no-cache src/hotshop_agent/reliability.py tests/test_circuit_lifecycle.py`：`2 files already formatted`，退出 0。

对原基线 worktree 复跑同镜像证明 format 失败为既有问题：

```powershell
docker run --rm --network none --mount type=bind,source=D:/Codex/Projects/hotShop,target=/review,readonly -w /review/agent --entrypoint python hotshop-agent:task20-test -m ruff format --check --no-cache tests/test_registry.py
git diff --exit-code a3e7a6c55040bbebc8760750af4b5cfcfd0406aa -- agent/tests/test_registry.py
```

前者同样退出 1，报告 `Would reformat: tests/test_registry.py`；后者退出 0。未修改该无关文件，未关闭格式门禁。

## 环境、安全与范围

宿主 Windows PowerShell；测试镜像 Python **3.12.14**，满足项目 `==3.12.*`。全部测试显式加载 `pytest_asyncio.plugin`，镜像网络禁用，源码只读挂载；仅格式化两个本任务文件时使用可写挂载。Git 提示 LF 将在未来 checkout 转为 CRLF，`git diff --check` 通过；未修改 Git 配置掩盖换行差异。熔断时间是 monotonic，与时区无关。

所有 Docker 执行为 `--rm` 短命容器，没有创建卷或外部服务，没有清理无关资源；mypy 缓存仅位于容器 `/tmp` 并随容器销毁。未运行 Maven clean、数据库诊断、浏览器链路、Qdrant 或长时压测。未推送、未合并 master。本分支仅修改 reliability.py、新测试与本文；集成后的联合验证仍由主 agent 执行。
