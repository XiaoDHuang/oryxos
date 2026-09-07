# 第 27 节：人推链路串联验收

日期：2026-09-06。工作分支 `027-lesson27-human-trigger`，实现基线 `7b1eceb`，复用 010，不新建 feature。**第 27 节已完成验收，T029–T041 共 13 项完成；尚未提交/推送。** 010 原 T001–T028 保留，当前台账共 41/41。

## 范围与用户决议

D27-01/02/03 已由用户确认，见 [开工对账](lesson27-preflight.md)：复用已批准分页列表与信封、将 `oryxos.root` 接入现有装配点、显式 Bootstrap 缺失/读取失败必须失败。用户随后强调管理台以 website 实际样式为准。

无新 Maven 模块/表/REST 端点，不改核心 Memory 与 SessionManager 公开签名；新增的公有生产类型仅为课件点名的 `MockChatModel`。所有代码/测试/回归由本会话主模型执行，未调度 Spark。

## 已完成验证

日志根目录：`.verification/lesson27/`，不纳入 Git。测试中只有合成语料；真实 key 从根 `.env` 解析后仅注入验证进程环境，不打印或写入源码/配置。

| 层次 | 结果 | 原始证据 |
|---|---|---|
| mock 红灯 | 5 项：1 failure / 4 未实现异常，未跳过 | `mock-red.log` |
| 首轮真实装配 | 发现常驻模式缺 ThreadPoolTaskScheduler；新夹具 Memory 标题不符合旧格式 | `flow-first.log` |
| 定向链路及前序装配 | 49/49（core 15、provider 5、memory 配置 9、tool 配置 13、boot 7） | `flow-second.log` |
| 真模型首轮配置 | 测试动态配置仅覆盖凭证、导致 Provider 列表条目丢 name；0 次有效真实对话 | `human-live.log` |
| 真模型人推集成 | 2/2，0 skipped；DeepSeek + 公开北京天气 + 临时 SQLite | `human-live-second.log` |
| 前端安装与构建 | 固定 npm 10.9.3 执行 ci/build 成功，Vite 7.3.6；产物已更新 | 当前工具输出；锁文件和静态产物 |
| Checkstyle | 所有模块通过；仅两个课件指定测试类名使用窄范围缩写例外 | `checkstyle-fixed.log` |
| 首次完整 verify | 新测试方法名连续大写导致 Checkstyle 拒绝，已修正 | `full-verify.log` |
| 第二次完整 verify | 既有 Mem0 70 项中 1 项出现上一操作 GET 串入当前 fixture，未改其断言/阈值 | `full-verify-second.log` |
| 原样复验 Mem0 | 同类污染再次复现于另一个参数用例，不能按偶发跳过 | `mem0-recheck.log` |
| 修复夹具后的定向回归 | 82/82：Mem0 70 + TLS 2 + CLI 3 + boot 7，全保留原断言 | `isolated-regression.log` |
| 第三次完整 verify | Tool 测试 JVM 启动前出现 Windows 跨盘 classpath 错误，非断言失败 | `full-verify-final.log` |
| 编译/测试/静态快速 verify | 九模块 + parent 全通过，定向 15/15；跳过 OWASP，仅是快速门禁 | `quality-fast.log` |
| 最终完整 verify | **BUILD SUCCESS，7m22s，441/441 默认测试，0 failure/error/skip；10 个 reactor 项含 parent 均 SUCCESS** | `release-verify.log` |
| 前序 Web 冒烟显式执行 | 6/6，0 skipped；`*IT` 不在 Surefire 默认命名匹配中，不能仅因无 integration 标签就算默认执行 | `web-smoke-final.log` |
| 最终 JAR 浏览器验收 | 实际 init/serve + API 写入合成事实，五页、四角色详情、刷新、空态、错误重试、官网 token、手机导航及无页面横向溢出；0 page error | `browser-xG7DeS/result.json`、`serve.log`、两张截图 |

默认测试实计：core 64 / storage 21 / provider 13 / memory 133 / tool 150 / web 40 / cli 8 / boot 12，共 441；channel-cli 没有独立测试源，其真实 CliChannel 由 boot 的无 key 与真模型链路覆盖。上述计数取本轮 Surefire XML，不沿用旧报告中的估计数。

完整门禁包含 Spotless、Checkstyle、P3C/PMD、SpotBugs/Find Security Bugs、OWASP（10 次 dependency-check 执行，含 parent）；未使用 skip 插件参数。既有 PMD 6 数据流引擎仍输出 15 条 `aktStatus is NULL: maximum Iterations exceeded`，保留为平台分析器局限，不把退出 0 当成完整数据流覆盖。未新增 OWASP 或 SpotBugs 安全抑制；Checkstyle 仅为两个课件固定测试类名增加缩写例外。

交付 JAR：`oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`，SHA256=`808ec436a5cf75b5caba427e2df4b9bc49694e4ec00daee2fc026b40dc6cfd5e`。浏览器直接使用该 JAR、随机 loopback 端口和独立测试工作区，结束后进程与端口已关闭，证据目录保留。前端背景 `#0a0a0a`、强调色 `#fb923c` 与官网一致；字体按浏览器 CSS 解析结果比较，避免将引号压缩误判为字体差异。Logo 与 `website/public/logo.svg` 的 SHA256 相同；截图已人工视觉检查，侧栏品牌区改为上下排列以避免宽 Logo 挤压标题。

真实模型通过内容：CLI 与 REST 天气分别 2 次 LLM + 1 次 http_get，四条 user/assistant/tool/assistant 历史及成功审计，天气对话不修改 Memory；显式保存一次、另一会话答复使用北京记忆；九个工具查询可见；两种真实模型触发的文件失败有失败审计；真实 OpenAI 协议的 loopback 503 故障使用合成凭证，失败持久化且服务仍健康。故障对端不是另一家模型服务，不接收真实 key。

mock 普通提问回显已注入上下文，是确定性接线验证，不视为真实推理。其固定 usage=1+1、model=mock-script 作为合成审计数据；真实模型证明由 HumanTriggerFlowIT 单独承担。

## 课件对账映射

| 守点 | 自动验证 |
|---|---|
| Provider 显式映射、mock 无 key、未选不注册 | MockChatModelTest；MockAgentE2ETest 真实装配 |
| 两轮一次工具、完整四角色、2+1 审计 | MockProviderFlowTest；MockAgentE2ETest；HumanTriggerFlowIT |
| CLI/REST/管理台查询同一数据 | 两个无 key 全链路 + HumanTriggerFlowIT + 最终 JAR 浏览器详情检查 |
| Memory 真实保存、跨会话可见、无关查询不写 | MockAgentE2ETest；HumanTriggerFlowIT |
| 九个 Tool 查询与注册表一致 | 三个全链路测试 |
| Provider / Sandbox / 工具失败也记账 | MockProviderFlowTest；HumanTriggerFlowIT |
| JPA 扫描及工作区隔离 | MockAgentE2ETest：4 repositories、默认 SQLite 随根派生、只读到临时 Profile |
| 显式上下文缺失或非法 UTF-8 | ContextLoaderTest；MockAgentE2ETest 真实 HTTP 500 且无模型调用 |
| 默认 init/status 使用覆盖根、未设属性仍默认 | InitCommandTest；最终全仓回归通过 |
| 单进程 REST 与 admin 根/子路由 | MockAgentE2ETest；最终 fat JAR 浏览器检查通过 |

## 实现审查与修正

- `speckit-analyze` 前置脚本成功解析到 010，未配置扩展 hook。27 增量 7 项 L27 要求均有任务覆盖；原 010 16 FR / 8 SC 保留为原验收范围。
- I27-01（HIGH）：StatusCommand 遗漏工作区覆盖，已追加 T040 并改为统一 CliFiles.workspace，扩展轻命令输出断言。
- T039：真实常驻模式不具备 ThreadPoolTaskScheduler，显式以现有配置类提供 Spring 生命周期托管的 Bean；仅 enabled=true 生效、用户 Bean 优先，chat 的默认条件不变。
- T041：原 Mem0 测试类共享同一 HTTPS 端口，仅更换 Dispatcher；上一用例临近截止已经发送的 GET 可延迟进入下一用例记录。现在每例新建独立端口，在旧端口仍占用时先分配新端口，然后关闭旧服务；复用 CA 避免重复密钥生成。所有操作 ID、请求次数、期限和错误分类断言保持，Mem0 生产传输未改。
- 没有删除行为断言、禁用测试、放宽阈值或新增全局安全忽略。D27-03 是用户批准的旧测试前提修正；测试夹具的 canonical Memory 标题和 Provider 同源列表绑定修正均保持原契约。
- 工作区根覆盖涉及的前序类：CoreEngineConfiguration、MemoryConfiguration、ToolConfiguration、CliFiles、InitCommand、StatusCommand 及 boot 默认数据源。模块职责与核心端口保持原方向。

Windows 验证运行器使用短 socket 路径 `-Djdk.net.unixdomain.tmpdir=.../.verification/jdk-sockets`。Surefire 的 `other has different root` 启动错误按原始 dumpstream 提示，仅在验证进程添加 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`；未写入生产配置，未禁用扫描器、测试或断言。原始失败日志继续保留。前端构建使用固定 npm 10.9.3（本机旧 npm 链接失效，pnpm 的 Windows 符号链接权限也不可用），从官方 registry 获取、SHA512 对照 registry integrity 验证后在 `.verification` 内运行；仓库的依赖版本按原有 lock 安装，没有升级 package.json。

## H4 与最终复审

1. 新增 mock 模型没有网络 IO；既有 File/Shell/HTTP/Notify 的 Sandbox.enforce 调用保持原位置，失败路径有真实工具/审计证据。
2. LLM 与工具成败分别写入真实 SQLite，按每个 Session 精确核对；不是 mock 审计回调或假 Store。
3. 以根 `.env` 三项实际 key 对新增/修改源码与文档做不回显扫描，0 个匹配文件；另扫描本轮日志与 Surefire XML 共 76 个证据文件，0 个匹配。测试材料使用合成数据。
4. Session ID 拼接仍只在 JpaSessionManager，新增 CLI/REST/浏览器代码只传三元组或使用返回的 id；核心 SessionManager/MemoryService 签名、schema.sql 不变。
5. 无新增 Reactor、CompletableFuture 或业务自建固定线程池；ThreadPoolTaskScheduler 按已有技术方案由 Spring 管理生命周期。
6. ProviderService 保留 `internalToolExecutionEnabled(false)`；mock 只给出调用意图，2+1 审计证明工具仍只在统一执行链执行。

最终增量复审：L27-001–007 覆盖 7/7，T029–T041 均有执行证据；I27-01、T039、T041 已关闭，本节新增未解决 CRITICAL/HIGH 为 0。前序公共端口和九模块依赖不变；只读管理台没有写操作入口。原始失败日志未删除，成功证据来自修复后重跑。

## 变更导读

- provider：新增 MockChatModel / MockChatModelTest，修改 ProviderConfiguration 显式注册。
- core：修改 CoreEngineConfiguration 的工作区/常驻调度器装配，修改 ContextLoader 与测试的显式引用/读取失败行为。
- memory/tool：修改 MemoryConfiguration、ToolConfiguration 的工作区接线；memory 另修复 HttpsFixture、HttpsFixtureTest、Mem0MemoryStoreContractTest 的测试生命周期，不动 Mem0 生产协议。
- cli：修改 CliFiles、InitCommand、StatusCommand，扩展 InitCommandTest。
- boot：修改默认数据源 URL；新增 HumanFlowFixture、MockProviderFlowTest、MockAgentE2ETest、HumanTriggerFlowIT。
- web：修改 SessionsView.vue 和 main.css，新增可提交的原有 package-lock.json，重新生成 static/admin；旧哈希 JS/CSS 由新产物替换，可从源码重新构建。
- 配置/文档：新增两个 config 样例；同步 README、AGENTS、四份事实源、010 spec/plan/tasks、开工对账和本报告；修改 gitignore 与两项窄范围命名规则记录。

## 验证命令与剩余人工项

```powershell
# 需要 NVD key 已在进程环境中；完整门禁预期 BUILD SUCCESS。
mvn clean verify
# 第 27 节无 key 测试，预期 12 项通过。
mvn -pl oryxos-boot -am test '-Dtest=MockChatModelTest,MockProviderFlowTest,MockAgentE2ETest' '-Dsurefire.failIfNoSpecifiedTests=false'
# 真模型组，需 DEEPSEEK_API_KEY，预期 2 项通过。
mvn -pl oryxos-boot -am test '-Dtest=HumanTriggerFlowIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest.excludedGroups='
# 既有 WebSmokeIT 显式运行，预期 6 项通过。
mvn -pl oryxos-boot -am test '-Dtest=WebSmokeIT' '-Dsurefire.failIfNoSpecifiedTests=false'
```

本机 Windows 验证还需前述临时 socket/跨盘 classpath 兼容参数，`.env` 不会自动被应用或 Maven 加载。

课件机器对账已判卷，真实模型与本机浏览器项目也已实际执行；部署到目标企业环境后仍需人工确认其实际 Provider、出口许可、浏览器与数据边界。第 28 节的定时→Notify、重启恢复、多 Agent 综合场景，以及 010 原报告的 200 并发和真实 60 秒超时，保持单独验收。本轮不执行 commit/push/package.sh，不修改既有个人 `.claude/settings.local.json`。
