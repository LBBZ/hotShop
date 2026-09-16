# TASK-21-RECONCILE-01：交付阻塞修复与复验

起点：`ebefde2ee158809f900a5912cbcda3ae7d58adad`，来自干净的`task-21-delivery`。
任务分支：`task-21-reconcile-01`；独立worktree：`D:/Codex/Projects/hotShop-task21-reconcile-01`。
未发现适用AGENTS.md；已读取TASK-21定义、原交付与阻塞报告。不合并、不推送master。

## 修复边界与兼容策略

- 共享写入枚举补全全部生产路径：工具调用、购买确认签发/消费/撤销及三个拒绝动作、预约补偿。
  资源补全购买草稿、购买确认、管理员工具、配置草稿和历史`FLASH_SALE_RESERVATION`。
  保留历史名称，不把它重命名为`SALE_RESERVATION`；两者均可查询。
- 用户与管理员Agent写入接口改用共享`AuditAction`/`AuditResourceType`参数；两个Task直接SQL也序列化共享枚举。
  不改变事务传播、拒绝策略、追加式触发器和权限。
- 读取响应的action/resourceType从封闭enum改为开放字符串，原样返回未知值；不删除、不跳过、不合并历史记录。
  筛选接受长度受限的字符串，参数化SQL以大小写敏感方式精确匹配；游标仍绑定筛选条件。
  actor/delegatedActor/result/source仍由数据库CHECK限定并与共享枚举一致，未来扩展这些封闭字段仍须同步迁移与契约。
- JSON既有字段和值不变，但生成客户端的两个enum类型/常量被移除，改为string；依赖旧生成enum导出的外部源码需更新。
  HTTP旧合法筛选仍有效，新增未知值筛选；不能把源码层的类型变化宣称为完全无影响。
- 前端显示原始代码及委托身份，动作/资源输入框可筛选未知值，变更筛选会重置游标。
- Task现有记录允许没有requestId/traceId，JSON的NON_NULL策略会省略它们；OpenAPI同步描述为可选字符串，
  前端以占位符显示，没有凭空补造关联ID。写入清点见[全部入口](task21-reconcile-audit-inventory.md)。

## 红绿证据

生产修复前先增加真实MySQL回归：合法Agent记录位于`limit+1`的第二行，`limit=1`的未过滤查询也返回500。
[红色结果](task21-reconcile-evidence/audit-red.txt)为1失败、0错误、0跳过，断言200实际500。
[可应用的测试补丁](task21-reconcile-evidence/red-test.patch)只增加测试，可在起点的另一个worktree用`git apply --unidiff-zero`应用后复现（已验证apply --check）。
首次准备测试误用了UPDATE，被追加式触发器拒绝；随后改为INSERT再取得上述HTTP红色证据，没有放松触发器。
第一次Maven命令未引用带点的`-D`参数，被PowerShell解析错误；这些执行失败不计作业务红色证据。

新增MySQL集成覆盖：真实Agent写入器的成功/失败、确认签发/消费/撤销及拒绝、管理员允许/拒绝/失败；
全部声明action/resource的历史SQL夹具、未知记录处于lookahead边界、未过滤分页无重无漏、大小写敏感筛选。
实际业务确认和权限流程同时由既有Portal集成测试与交付浏览器覆盖，写入器夹具不冒充全部业务流程操作。

## 中文RAG与未决历史异常

不改Embedding算法、维度、知识语料、全局阈值0.15和索引版本；无需算法迁移或强制旧索引换版。
全新demo仍按README重建自己的索引。FakeModel/deterministic固定，不调用付费模型。

| 问题/状态 | 明确预期 | 依据 |
| --- | --- | --- |
| `售后申请应从哪里发起？` | hit，有正确引用 | 直接询问静态语料已有句子“售后申请应从订单详情页发起” |
| `How does the after-sales return policy work?` | hit，有正确引用 | 同一文档已有英文售后政策句子 |
| 原问题`售后退换申请应该怎么做？` | empty，明确拒答，零引用 | 原问题保留为独立案例；确定性特征哈希不具备语义改写保证，原报告分数0.062578276低于0.15；不是把失败改称命中 |
| Qdrant断连 | unavailable，暂不可用，零引用 | 必须在运行中的Agent请求验证，不能以另起诊断进程替代 |

引用核对`after-sales-general` / `1.0.0` / `after-sales-general-0000`，title/source来自版本化JSON；
对应chunk确实包含从订单详情页发起售后申请的内容。FakeModel引用机制通过不证明真实模型回答质量。

日志新增安全分类`embedding_failure`、`qdrant_connect/timeout/transport/http/response/operation`、
`retrieval_runtime/unexpected`；每次检索记录outcome，关联requestId/traceId/runId。
不记录问题、正文、异常消息、堆栈、URL凭据或令牌；异常时清空证据，避免返回部分候选。
单元测试用敏感哨兵值验证诊断不泄漏，并分别验证连接、超时、HTTP、解析和运行时异常。

**历史`2026-09-15T17:44:58.671717Z`的RetrievalError根因仍未决。** 原日志信息不足；注入Qdrant故障只能
验证当前降级和恢复，不证明历史异常就是断连，不宣称已修复该未知根因。原失败证据保持在
[TASK-21阻塞报告](task-21-blockers.md)和[原交付报告](task-21-delivery.md)。
本轮只读复查原停止容器日志，确认该次只有泛化RetrievalError且没有runId，
脱敏字段归档见[历史诊断](task21-reconcile-evidence/historical-rag-diagnostics.json)。未启动或修改原容器。

## 执行环境与复验入口

本机Windows / PowerShell7；宿主Microsoft OpenJDK21.0.8+9，Maven Wrapper3.9.16；
Docker Desktop Linux、Node22.20.0、pnpm10.15.0。依赖镜像/包缓存复用，业务卷和密钥隔离。
这不是另一台新机器验证，也未测零缓存首次拉取成本。

PowerShell7，工作目录为本任务worktree；Java命令要求`JAVA_HOME`指向本机Java21（本次为`D:/Env/JDK/MicrosoftOpenJDK21`）。

```powershell
Set-Location D:/Codex/Projects/hotShop-task21-reconcile-01
$env:JAVA_HOME='D:/Env/JDK/MicrosoftOpenJDK21' # 换成自己的Java21安装路径
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Duser.timezone=UTC'
./mvnw.cmd -B -ntp verify
corepack pnpm@10.15.0 --dir web install --frozen-lockfile
corepack pnpm@10.15.0 --dir web exec playwright install chromium
$demoProject = 'hotshop-task21-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
pwsh -NoProfile -File script/task21-demo.ps1 -Action Start -ProjectName $demoProject
corepack pnpm@10.15.0 --dir web exec playwright test --config playwright.delivery.config.ts
node script/verify-reconcile-rag.mjs $demoProject http://127.0.0.1:18080
pwsh -NoProfile -File script/task21-demo.ps1 -Action Stop -ProjectName $demoProject
```

断连脚本核对本worktree的env目录、Compose project/service及唯一容器ID；只停止/恢复所属Qdrant，
finally尝试恢复，不删除数据，验证Agent进程没有重启。仅在专用测试数据上运行，不能指向共享服务。

## 保持的局限

刷新中断恢复、性能优化、微服务、公网部署、真实模型效果和完整故障矩阵均未扩展。
TASK-20的5000 requested RPS目标未达，原窗口、完成量、dropped、延迟及队列TTL口径保持不变。
未在另一台新机器验证，不宣布全项目终验通过。

## 命令准备与失败记录

- 早期全Java运行在宿主默认Asia/Shanghai：既有`durableTimelineRecoversAfterLastEventIdAndServiceRecreation`
  期望06:00Z、实际14:00Z。保留[失败](task21-reconcile-evidence/java-local-timezone-failure.txt)，不改业务或测试断言，
  改为显式UTC测试JVM后重新验证。
- 初次共享枚举签名修改遗漏确认Controller拒绝分支，编译失败；清点后补齐三种拒绝动作和Task补偿历史类型。
  编译失败不能记为运行测试通过。
- 本机原生OpenAPI CLI选到了7.25.0而不是仓库固定7.14.0。用现有`HOTSHOP_OPENAPI_GENERATOR_DOCKER=1`
  重新生成，提交中不含新版生成器造成的无关更改。Windows检出CRLF使四客户端字节比较失败；将本worktree生成
  文件规范为LF再跑门禁，未变更其余客户端的Git内容。门禁未被放宽。
- 补充可空HTTP关联字段时，最初写成nullable类型；保守兼容门禁拒绝string→string/null。
  因真实NON_NULL响应会省略字段，最终使用准确的“可选string”，不改比较器来接受错误描述。
- 对普通超时摘要的额外探针最初错误地期待保留`reservationNo:null`；真实MySQL回归返回200，
  当前ObjectMapper在写入前省略该值，故缺字段断言失败。保留[准备失败记录](task21-reconcile-evidence/task-null-expectation-corrected.txt)，
  最终测试准确断言省略reservationNo/requestId/traceId；没有据此虚称新增业务500或修改Map读取行为。
- 首个空卷项目`hotshop-task21-reconcile091601`：首次浏览器4通过/3失败（45.8s），SSE响应体事后读取和
  含时间的单元格精确文本定位失败；第二次5通过/2失败（38.3s），重复管理员登录触发限流，及响应体读取失败。
  两次不作为最终验收通过。修正测试观察方式，不放宽限流，不修改Agent SSE实现。
- 被动观察脚本只克隆真实fetch流，保留rag.completed/done的结构化事件供断言；不拦截路由、不伪造响应、
  不另起诊断进程替代运行中的服务。测试日志不包含问题正文、令牌或完整请求。

运行时契约与客户端复验（PowerShell7、仓库根目录，需先package产生jar）：

```powershell
pwsh -NoProfile -File script/generate-openapi.ps1 -UseExistingPackages
python script/check_openapi_compatibility.py
$env:HOTSHOP_OPENAPI_GENERATOR_DOCKER='1'
# Windows只规范本worktree的生成文件换行；Git内容不应因此改变。
Get-ChildItem web/src/api/generated -Recurse -Filter *.ts | ForEach-Object {
  $text = [IO.File]::ReadAllText($_.FullName).Replace("`r`n", "`n")
  [IO.File]::WriteAllText($_.FullName, $text, [Text.UTF8Encoding]::new($false))
}
corepack pnpm@10.15.0 --dir web api:check
```

## 最终验证结果（2026-09-16，本次工作树）

各行是独立运行，不将不同版本、跳过或筛选数量相加。

| 验证对象 / 实际入口 | 实际结果 | 版本化证据 |
| --- | --- | --- |
| Java全reactor，`mvnw.cmd -B -ntp verify`，Java21/UTC、Testcontainers真实依赖 | 320通过，0失败/错误/跳过；6分19秒；其中AdminIdentitySecurityTest为20项 | [汇总及每套件](task21-reconcile-evidence/java-utc-summary.json)、[reactor](task21-reconcile-evidence/java-reactor.txt)、[审计绿色](task21-reconcile-evidence/audit-green.txt) |
| Agent test镜像，pytest `-m 'not qdrant'` | 275通过，6跳过，8排除；33.86秒。跳过的是未设置AGENT_CONTAINER_IMAGE的容器安全测试 | [结果](task21-reconcile-evidence/agent-tests.txt) |
| 同一test镜像，pytest `-m qdrant`，真实隔离Qdrant | 8通过，281排除；22.98秒 | [结果](task21-reconcile-evidence/agent-qdrant.txt) |
| Agent ruff check / format --check / mypy src | 全部通过；format、mypy各55文件 | [ruff](task21-reconcile-evidence/agent-ruff.txt)、[format](task21-reconcile-evidence/agent-format.txt)、[mypy](task21-reconcile-evidence/agent-mypy.txt) |
| Web `pnpm test` / `lint` / `typecheck` | 20文件87测试通过，9.10秒；lint/typecheck通过 | [单测](task21-reconcile-evidence/web-tests.txt)、[lint](task21-reconcile-evidence/web-lint.txt)、[类型](task21-reconcile-evidence/web-typecheck.txt) |
| 运行时OpenAPI兼容检查、固定7.14.0生成器四客户端漂移门禁 | 全部通过；无修改比较器 | [兼容](task21-reconcile-evidence/openapi-compatibility.txt)、[客户端](task21-reconcile-evidence/client-drift.txt) |
| 最终空卷项目03，真实Chromium交付套件 | 7通过，0跳过/重试，35.5秒；真实构建产物经Nginx访问后端 | [浏览器](task21-reconcile-evidence/browser-accepted.txt) |
| 项目03，真实浏览器请求并停止/恢复其Qdrant | 中文hit、原改写empty、英文hit、断连unavailable、恢复hit；同一Agent进程，日志request/run关联一致 | [结构化结果](task21-reconcile-evidence/rag-outage-accepted.json) |

最终隔离项目为`hotshop-task21-reconcile091603`，启动沿用README的Start命令，没有最小启动补丁。
[镜像和挂载身份](task21-reconcile-evidence/isolated-environment.json)记录本次隔离容器、卷与镜像。
[浏览器前](task21-reconcile-evidence/fresh-before-browser.txt)：订单0、商品913001库存/expected stock均50、version0、11条迁移；
[浏览器后](task21-reconcile-evidence/fresh-after-browser.txt)：订单5、库存/expected stock均47、version6、外键0。
这些是测试夹具的变化，不是压测或生产用户规模。

项目02也曾独立7通过（32.8秒）并完成断连恢复；随后补齐可选关联字段契约，才重新创建项目03。
保留[02浏览器](task21-reconcile-evidence/browser-reconcile02.txt)、[02故障结果](task21-reconcile-evidence/rag-outage-reconcile02.json)，
以及[01首次失败](task21-reconcile-evidence/browser-first.txt)、[01第二次失败](task21-reconcile-evidence/browser-second.txt)，不混入最终统计。
最终浏览器执行后仅为测试文件增加TypeScript类型标注/断言并格式化，通过lint/typecheck；没有改变运行时行为或应用源码，未因此重跑浏览器。
[源码SHA256清单](task21-reconcile-evidence/source-manifest.json)绑定提交前的最终内容，应用构建和执行发生在提交前工作树。

本次三个demo项目及独立Qdrant测试容器均已停止，容器/业务卷/镜像/密钥目录保留。
[停止后快照](task21-reconcile-evidence/containers-final.txt)中Task退出137是Compose停止阶段超时终止的退出状态，未据此声称优雅退出已经验证。
未删除用户原有资源；OpenAPI脚本和Testcontainers仅清理自己拥有的临时资源。

## 回归命令补充

以下均在仓库根目录PowerShell7执行，Docker可用；测试镜像无需付费模型凭据：

```powershell
docker build --target test -t hotshop-reconcile01-agent-tests-final:local agent
docker run --rm hotshop-reconcile01-agent-tests-final:local python -m pytest -p pytest_asyncio.plugin -p no:cacheprovider -m 'not qdrant'
docker run --rm hotshop-reconcile01-agent-tests-final:local python -m ruff check src tests
docker run --rm hotshop-reconcile01-agent-tests-final:local python -m ruff format --check src tests
docker run --rm hotshop-reconcile01-agent-tests-final:local python -m mypy src
corepack pnpm@10.15.0 --dir web test
corepack pnpm@10.15.0 --dir web lint
corepack pnpm@10.15.0 --dir web typecheck
```

本次真实Qdrant测试实际使用专用network和容器，命令为：

```powershell
# 这些名称属于本轮已保留的资源；复验时先确认名称归属，不指向用户共享服务。
docker start hotshop-reconcile01-qdrant0916
docker run --rm --network hotshop-reconcile01-ragtests0916 -e AGENT_QDRANT_URL=http://hotshop-reconcile01-qdrant0916:6333 hotshop-reconcile01-agent-tests-final:local python -m pytest -p pytest_asyncio.plugin -p no:cacheprovider -m qdrant
docker stop hotshop-reconcile01-qdrant0916
```

该入口依赖本机保留的专用测试资源；独立新环境优先用上文Start、完整浏览器和断连脚本，自动建立自己的Qdrant与空业务卷。

## 验收映射与结论

| 用户要求 | 结论与文件 |
| --- | --- |
| 先红后绿，完整审计写入、历史未知值、limit+1边界、分页与筛选 | 满足；[清点](task21-reconcile-audit-inventory.md)、真实MySQL红绿、20项Admin集成；权限和追加约束保持 |
| OpenAPI、生成客户端、前端显示筛选同步 | 满足；admin baseline、两份生成文件、admin-audit-page及单测；开放代码的源码兼容影响已披露 |
| RAG empty/unavailable、安全诊断与实际恢复 | 满足本轮验证要求；同进程真实断连，敏感哨兵回归；历史瞬时异常根因仍未决，未宣称已修复 |
| 中英文引用、原失败改写用例 | 满足已明示的确定性演示边界；原改写安全拒答，不能宣称泛化中文语义检索已达标 |
| 新隔离空卷启动与完整浏览器交付 | 本机满足；交易、Mock支付、秒杀、库存冲突、审计、确认、权限拒绝及RAG均覆盖；另一台新机器未验证 |
| 回归与文档证据 | 已执行上述范围；[README](../../README.md)、[演示](../delivery/demo.md)、[证据索引](evidence-index.md)、[简历边界](../delivery/career.md)已同步 |
| 保持本轮范围、旧失败和性能事实 | 满足；旧报告仅增加后续入口，未删除原失败；后续工作见[下一轮清单](../delivery/next-iteration.md) |

这些结果不覆盖长期稳定性、完整故障矩阵、真实模型效果、完整容器安全矩阵或另一台新机器，不能据此宣布全项目终验通过。

## 交付静态检查

`node script/verify-task21-docs.mjs`检查330个本地链接并实际渲染10张Mermaid，0失败；
范围是相对原TASK-21基线的变更Markdown，不验证远程URL或页内锚点。
四个修改的非生成前端文件Prettier检查通过；敏感信息检查使用仓库配置及固定Gitleaks镜像扫描暂存差异，
结果和`git diff --cached --check`状态在最终提交前记录。密钥与完整服务日志没有提交。

敏感信息初检的两处命中均为源码清单中的SHA256摘要（字段名包含API），不是凭据；
清单改为path/sha256结构后重新扫描，无泄漏，未增加扫描豁免。证据文本仅规范行尾空白，原结果不变。
最终暂存差异`git diff --cached --check`通过。
