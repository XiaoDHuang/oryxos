# 第 27 节人推链路串联：开工对账与决议

日期：2026-09-06。分支：`027-lesson27-human-trigger`，基线：`7b1eceb`。

本节由 `oryxos-lesson-dev 27` 触发，属于串联课，不创建新的 feature；现有 `.specify/feature.json` 仍指向 010。本文件记录实施前核对与可评审方案，不是运行验收报告。

## 已完成的准备

- 已完整读取第 27 节课件、技术方案 §12、前序 16/17/18/19/20/22/24/25/26 节的交付物小节，并核对宪法 v3.0.0。
- 前序核心类 60 项均存在；课件点名的前序测试类 29 项均存在。这里只核验存在性，没有重新执行测试，不把存在视为行为通过。
- 九个内置工具均有真实实现：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`notify`、`save_memory`、`recall_memory`。
- SQLite 脚本包含 `sessions`、`llm_calls`、`tool_invocations` 及 007 的 `memory_entries`；启动类显式声明 JPA 实体/仓储扫描包。
- 010 已有会话列表、详情、Memory、Tool 等查询接口；Vue 会话页已请求真实分页列表；`static/admin/index.html` 已提交。
- 工作区基线没有产品代码未提交改动，唯一既有未跟踪项为 `.claude/settings.local.json`，保留原状。
- 005 的五个扩展工具属于已批准延期 DR-003，不能因新版课件出现扩展交付物而将其误判为第 27 节的前置缺失，也不能伪称其已实现。

## 已由用户确认的三个适配点

### D27-01：复用 010 会话列表契约

事实：

- 课件 §2.5 假设会话列表缺失，提出新增 `SessionManager.listRecent(int)`、默认最近 100 条、可选 `status`，摘要包含消息条数。
- 010 已经用户批准并实现 `SessionPage listSessions(int page, int size)` 和 `GET /api/v1/sessions?page=0&size=20`，size 最大 100，返回分页信封。管理台正在使用此接口。
- 当前 `SessionSummary` 没有消息条数字段，详情响应已有 `totalMessages`；创建接口要求 `profileName`、`userId`，不能直接照抄课件的 `{"profile":"mock-agent"}` 示例。

建议决议：本节保留已有分页接口、默认页大小、创建请求字段和信封，使用 `page=0&size=100` 完成列表对账，以详情的 `totalMessages` 对账消息条数；不再增加重复的 `listRecent`。课件的可选 status 过滤和列表消息条数不在本次兼容方案中新增，明确记录这一差异，不宣称已交付。README/curl 和测试使用实际返回的 sessionId，不手工拼接或假设 id 格式。

理由：列表能力已经交付；改回课件示例会改变正在使用的公共契约，与三面同源目标无关。此项需要确认，是因为课件点名的方法/默认值与已有批准的契约不同。

### D27-02：工作区配置接入现有装配点

事实：

- 课件要求在 `OryxOsRuntime` 增加 `oryxos.root` 系统属性，供整机测试隔离工作区。
- 仓库不存在 `OryxOsRuntime`。现有职责分布在 `CoreEngineConfiguration`、`MemoryConfiguration`、`ToolConfiguration`、`CliFiles`，这些位置各自包含 `.oryxos` 路径；SQLite URL 在 boot 的 `application.yaml`。
- 单独覆盖 Core 的 Profile 路径不足以隔离 Memory、MCP 配置和数据库。

建议决议：保留 `oryxos.root` 字面量与默认 `.oryxos`，由现有配置点共同消费，并让默认 SQLite 路径随工作区根派生；显式数据源配置继续优先。不创建 `OryxOsRuntime`，不迁移 Maven 模块职责或增加另一套运行时。CLI 轻/重命令、Profile/Bootstrap/Skill、Markdown、MCP 配置与整机测试须使用一致的路径口径。隔离测试显式提供只覆盖测试目录和 loopback 对端的白名单；选择测试目录不自动扩大安全许可。

理由：这是将课件要求落到实际工程结构，需先确认点名类的替代位置。默认行为和既有 Memory 端口不变。

### D27-03：显式 Bootstrap 缺失应失败

事实：

- 第 27 节 §2.3 要求 Profile 明确引用的文件缺失直接报错。
- `specs/002-react-loop/spec.md` FR-004 同样要求“Profile 显式引用的文件缺失 MUST 报错”。
- 当前 `ContextLoader.load` 对显式 Bootstrap 缺失只 WARN 后继续；`ContextLoaderTest.missingBootstrap_warnsAndContinues` 还将此行为固定为通过条件。

建议决议：按 002 规格和第 27 节要求修复实现：显式引用的 Bootstrap 缺失时记录可定位信息并失败，引用的 Skill 缺失继续失败，空引用列表不产生隐式必需文件。把上述错误前提的旧测试改为断言失败，并补真实链路负例；不删除该场景、不降低断言强度。其他上下文读取失败也须复核是否静默返回空内容。

理由：这是已证实的规格/实现/测试冲突。技能反作弊规则明确写道“认为测试错，停下报告”，因此在取得确认前不修改该断言。

## 拟实施范围与执行顺序

以下均为待实施，不是已完成任务。确认后在 010 的任务台账追加本节可追踪条目，不重编号已完成任务。

1. 记录以上决议，同步受影响的当前契约说明与四份事实源中的相关落点；不擅改宪法、不追溯改写 006 归档范围。
2. 按课件新增 `MockChatModel` 与 `MockChatModelTest`，在实际 `ProviderConfiguration.chatModelRegistry` 中显式接入 `mock`；仅显式配置该名字才启用，零 key、零远端模型连接，其余 Provider 仍按既有凭证规则处理。Profile 校验继续使用显式注册名字集合。
3. 统一 `oryxos.root` 工作区隔离接线并验证默认路径兼容；修复 D27-03，保留真实上下文、Session、Memory、Tool 和审计路径。
4. 在 boot 的系统集成测试位置新增 `MockProviderFlowTest`：手工组合真实服务，只有模型为确定性脚本；断言 ReAct 两轮、一次 `save_memory`、真实 `MEMORY.md` 写入、完整会话、SQLite 中 2 条 LLM 与 1 条 Tool 审计，以及查询端点可见同一份状态。
5. 新增 `MockAgentE2ETest`：`@SpringBootTest` 随机真实 HTTP 端口 + 临时 SQLite/工作区，通过实际 HTTP 创建/发送/查询；验证跨会话记忆、九工具、审计、JPA 扫描与运行隔离。两个无 key 测试进入默认 gate。
6. 新增 `HumanTriggerFlowIT`，打 `@Tag("integration")`：真实 Provider/受控天气请求的两次 LLM + 一次 `http_get` 对账，Memory 不应被天气对话改写；显式保存、新会话召回、CLI/REST/管理台数据一致；复现 Provider 故障、Sandbox 拒绝与工具失败并核对失败审计。真实凭证缺失或外部环境未获准时如实保留该层未通过，不能用 mock 结果替代。
7. 复用 010 管理台真实会话列表；补课件要求的点击读取详情交互时按 `oryxos-admin-ui` 实施，只调用已有详情端点。构建管理台，验证真实 HTTP 的 `/admin` 与子路由回落、API 路径互不影响。
8. 提供 `config/application.yml.example` 的显式 mock 样例与 README 可复制的无 key 流程；保留实际配置层级、请求字段、动态 sessionId 和单 JAR 发布形态。课件的 `bin/start.sh` 当前不存在，不将其写成已可运行命令。
9. 跑受影响测试、默认无 key 全链路与不跳插件的 `mvn clean verify`，保留原始日志；按需执行真实模型集成。最后逐项记录课件对账、测试映射、修改范围、未完成的真实环境/人工验收项。

Mock 模型输出及 usage 必须明确为合成测试值，不能用于宣称真实模型 token 测量或语义效果。显式 mock 只替代模型，不使用假 Store、假 Session 或仅 mock 审计写入冒充整机验收。

## 决议与执行状态

用户已于本会话明确批准 D27-01/02/03。实施与验收已完成，结果见 [本节验收报告](lesson27-acceptance.md)；本文件保留开工时的事实与方案依据。

最初暂停依据为技能 `.agents/skills/oryxos-lesson-dev/SKILL.md` 的“需要修改任何已定字面量”软门禁与“认为测试错，停下报告”条款。D27-01/02 涉及课件与现有契约的适配，D27-03 涉及错误测试前提；用户批准后已解除该暂停点。
