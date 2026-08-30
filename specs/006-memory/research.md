# Research: 三层 Memory 核心能力

## Decision 1: 核心端口反转，避免 Maven 循环

**Decision**: `MemoryService` 与其签名使用的 `MemoryScope` 放在 `oryxos-core`；`MemoryServiceImpl`、`LongTermMemory`、`MemoryTools` 放在 `oryxos-memory`。`oryxos-core` 不依赖 memory 实现模块。

**Rationale**: 当前 `oryxos-memory` 已依赖 `oryxos-core` / `oryxos-storage`。若 `PromptBuilder` 反向引用 memory 模块会形成 Maven 循环。端口归核心、实现归下游与现有 `LlmGateway` 模式一致。用户已显式批准，并同步 `AGENTS.md`、TechnicalSolution、AiProgrammingGuide 和第22节课件。

**Alternatives considered**:

- 让 core 直接依赖 memory：形成循环，拒绝。
- 在 core 新增第二个 `MemoryContextProvider`：制造重复接口墙和额外公共概念，拒绝。
- 用反射或 Bean 名从 core 查 memory：类型不安全且隐藏依赖，拒绝。

## Decision 2: 端口直接返回消息列表，不新增上下文 DTO

**Decision**: `MemoryService.buildContext(Session session, int maxHistoryTurns)` 返回 `List<Message>`：有实际长期内容时首项为独立 `SystemMessage`，随后为最近会话消息；空长期记忆不产生空消息。

**Rationale**: Message 与 Session 都已属于 core 现有依赖；这能保持角色、Tool Response 等消息语义，又让会话截断与长期记忆组装经同一端口交付，不增加 `MemoryContext` 公共类型。

**Alternatives considered**:

- 返回渲染后的单一字符串：会丢失消息角色和 Tool Call 结构，拒绝。
- 新建 `MemoryContext` record：本节不需要额外公共概念，拒绝。
- MemoryService 只返回长期记忆、PromptBuilder 继续自行截断历史：统一门面不完整，拒绝。

## Decision 3: PromptBuilder 保持兼容入口

**Decision**: 新增接收 `MemoryService` 的正式构造入口；既有二参构造器保留并委托给 core 内部空记忆实现。`CoreEngineConfiguration` 用 `ObjectProvider<MemoryService>` 接真实实现，memory 模块缺席的 core 单测仍可运行。

**Rationale**: 第22节明确把 PromptBuilder 列为改造点，但前序测试和外部调用不应因构造签名直接失效。两条构造入口最终必须汇入同一 `build` 实现，不能形成第二条 Prompt 组装路径。

**Alternatives considered**:

- 删除旧构造器并一次修改所有调用方：改动更大且没有用户价值，拒绝。
- 在 PromptBuilder 内直接读文件：破坏端口边界，拒绝。

## Decision 4: 只有一个工作区级 MEMORY.md 后端

**Decision**: 唯一长期记忆文件是 `<workspace>/memory/MEMORY.md`，UTF-8，精确整行 header 为 `## 核心记忆` / `## 归档记忆`。每条新增记录写成 `- [yyyy-MM-dd] <content>`。

**Rationale**: 与需求/技术方案的核心阶段最短链路一致；人可读、可备份、无新依赖，并能区分始终在场的核心与按需检索的归档。

**Alternatives considered**:

- SQLite `memory_entries`：事实源明确核心阶段 Memory 无 schema，拒绝。
- Mem0/其他外部服务：增加部署和数据流转，拒绝。
- 多文件按 topic：扩大文件契约且当前量级不需要，拒绝。

## Decision 5: 兼容旧模板，歧义结构失败关闭

**Decision**:

- workspace 不存在：失败并提示先 init，不在其他路径补写；
- memory 目录/文件缺失：在已存在 workspace 内创建标准模板；
- 空文件或旧 `# Long-term memory` 模板：升级成双分区；
- 无分区的旧业务内容：完整迁入归档区，符合保存 scope 的默认值；
- 仅有一个合法分区：补齐另一个并逐字保留原内容；
- header 重复、倒序或内容包含独占整行伪 header：明确失败，写入前文件保持不变。

**Rationale**: 项目旧 `InitCommand` 已生成只有标题的文件。默认迁入归档既不丢用户内容，也不擅自提升为“永不截断”的核心记忆；重复/倒序无法安全消歧，必须失败关闭。

**Alternatives considered**:

- 任何旧格式都覆盖为新模板：会丢数据，拒绝。
- 任意猜测重复 header 的归属：可能错误截断核心内容，拒绝。

## Decision 6: 4000 字只限制注入视图，不裁物理文件

**Decision**: `load()` 每次读磁盘，核心正文完整返回，归档正文超过 4000 个 Java `char` 时只返回最后 4000；物理文件不因读取而缩短。`recallByKeyword` 始终检索未截断的完整归档正文。

**Rationale**: 技术方案要求核心不截断、归档注入限长，同时 recall 应能找到较早归档。若 load 物理删除旧内容，长期记忆会不可逆丢失。

**Alternatives considered**:

- 按条目数量截断：改变已定 4000 字口径，拒绝。
- 物理裁剪文件：破坏跨会话长期保存和旧记忆 recall，拒绝。
- 上下文总结压缩：扩展阶段能力，拒绝。

## Decision 7: 不缓存，写入用同路径 JVM 锁和原子替换

**Decision**: 每次 load/recall 重新读文件。初始化、修复和 append 对规范化绝对路径使用 JVM 内共享锁；写入通过同目录临时文件后 `ATOMIC_MOVE + REPLACE_EXISTING`，文件系统不支持原子移动或Windows拒绝覆盖式原子移动时回退普通`REPLACE_EXISTING`，finally清理临时文件并对清理失败记不含路径的WARN。

**Rationale**: 单个 `synchronized` 实例不能保护同一路径的多个实例。共享路径锁可防同一运行实例丢写；临时文件替换避免读到半写内容。锁只用于同步，不保存记忆内容，因此不是缓存或业务状态。

**Alternatives considered**:

- 直接 append：并发下可能交错或部分写，拒绝。
- 仅实例方法 synchronized：多实例指向同文件仍可丢写，拒绝。
- 引入数据库锁：违反文件式核心范围，拒绝。

## Decision 8: MemoryTools 必须显式作为内置工具注册

**Decision**: `oryxos-tool` 增加对 `oryxos-memory` 的内部模块依赖；`ToolConfiguration` 通过 `ObjectProvider<MemoryTools>` 在 registry freeze 前把它加入内置集合并使用现有可信 guard。普通 Java Plugin 的默认拒绝保持不变。

**Rationale**: 当前自动扫描会把未知 `@Tool` Bean 当普通 Java Plugin，注册后默认 guard 拒绝执行。Memory Tool 是受控工作区内置能力，必须显式信任；仍由 `AnnotatedToolAdapter → ToolExecutor` 唯一路径执行并落审计。

**Alternatives considered**:

- 全局放行所有 `@Tool` Bean：破坏第20节插件安全边界，拒绝。
- MemoryTools 绕过 ToolRegistry 直接调用：形成第二条执行/审计路径，拒绝。
- 用包名/Bean 名反射判断可信：隐式且易漂移，拒绝。

## Decision 9: 输入、检索和错误语义

**Decision**:

- `null` scope 在服务层按 `ARCHIVAL`，Tool 的空/空白 scope 也缺省归档；
- Tool scope 使用 `trim()` 后仅把ASCII A–Z逐字符折叠为小写，再与core/archival常量精确比较，避免Unicode大小写转换API；
- 空内容、空关键词、非法 scope、伪 header 内容为 `IllegalArgumentException`；
- workspace/文件/结构/IO 失败为保留 cause 的 `IllegalStateException`，对外中文消息不含绝对路径；
- recall 使用大小写敏感 `String.contains`，只搜归档，按文件顺序返回匹配行；无命中是空列表，由 Tool 映射成 `没有找到相关记忆`。

**Rationale**: 语义简单、可测试、无额外分词或正则能力；非法输入不会静默写错区块，错误也不泄漏本地路径。

**Alternatives considered**:

- 大小写不敏感/模糊搜索：改变“简单包含匹配”范围，拒绝。
- 对非法 scope 自动回退归档：会掩盖 Agent 参数错误，拒绝。

## Decision 10: 初始化和验收不依赖进程当前目录

**Decision**: `LongTermMemory` 接受显式 workspace `Path`；生产配置传 `.oryxos`，测试传 `@TempDir`。`InitCommand` 保留 Picocli 无参构造，再加包级 Path 构造入口，并把默认模板改为双分区。

**Rationale**: 修改 `user.dir` 对 `Path.of` 不可靠且污染并行测试。显式路径让文件边界和测试隔离可验证，不产生新配置键。

**Alternatives considered**:

- 测试全局切换工作目录：不稳定且影响并发测试，拒绝。
- 新增 memory path 配置键：本节明确无新配置，拒绝。
