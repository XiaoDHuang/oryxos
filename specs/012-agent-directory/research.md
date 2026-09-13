# Phase 0 Research: 插件化 Agent 目录

11 条设计决策，全部在本地代码库核实（标注核实位置）。无 NEEDS CLARIFICATION 遗留。

## D1 frontmatter 解析复用 SnakeYAML，规则与 ProfileLoader 对齐

- **Decision**: `AgentLoader` 用既有 SnakeYAML 解析 `AGENT.md` 的 `---` 围栏 frontmatter；键 snake_case→camelCase 归一化 + `${ENV_VAR}` 占位从进程环境解析，规则与 `ProfileLoader` 完全一致。
- **Rationale**: 两条来源必须同构同规矩；SnakeYAML 已在 `ProfileLoader` 使用（`oryxos-core/.../profile/ProfileLoader.java:75`），无新依赖。
- **Alternatives**: 引入 frontmatter 专用库（新依赖，拒）；手写解析（重复造轮子，拒）。

## D2 正文与资源目录经既有 `identity.promptFile` 字段绑定

- **Decision**: `deriveProfile(agentDir)` 把 frontmatter 各键一一映射到 `Profile`，并把 `identity.promptFile` 置为 `agents/<name>/AGENT.md`（相对工作区）。frontmatter 的 `identity.prompt`（人格）仍走内联；正文经 promptFile 每次现读注入。
- **Rationale**: `Profile.Identity.promptFile` 是技术方案 §8.2 已定义的既有字段（"prompt 或 prompt_file 二选一"），全库 grep 证实此前无任何消费者（仅 `Profile.java:48` 声明）——定义其语义不是新增字段，也不改任何已定字面量。正文必须每次触发从磁盘现读（验收 SC-003：改正文 0 次重启），内联进 Profile 会在扫描期固化、违反该性质。
- **Alternatives**: 正文内联进 `identity.prompt`（丢"改完即时生效"，拒）；给 Profile 加 `agentDir` 新字段（新增对外概念，软门禁，拒）。

## D3 ContextLoader 加法：promptFile 现读、去 frontmatter

- **Decision**: `ContextLoader.load(Profile)` 在 bootstrap/skills 之外增加：若 `identity.promptFile` 非空，从工作区根解析路径现读文件；内容以 `---` 行开头且存在闭合 `---` 行时剥掉 frontmatter，只把正文段拼进上下文。文件缺失按既有"显式引用必须失败"原则抛 `IllegalStateException`。
- **Rationale**: 课件交付物原文"ContextLoader 注入 Agent 正文（从目录主文件读，去 frontmatter）"；无缓存是该类既有硬规则（`ContextLoader.java:12`）。无 frontmatter 的普通 prompt 文件剥离逻辑为空操作，行为兼容。
- **Alternatives**: 派生时把正文写进 bootstrap 列表（bootstrap 读取不剥 frontmatter，且语义错配，拒）。

## D4 同一套校验抽成 `ProfileValidator`（package-private）

- **Decision**: 新增 `core.profile.ProfileValidator`：`validate(Profile)` 校验 name 非空、provider.name 在全局 provider 名集合内，失败抛 `IllegalArgumentException`（固定中文消息）。`ProfileLoader` 改为调用它、catch 后按现有格式记错误日志并跳过（16 节行为不回退）；`ProfileRegistry.register` 直接调用同一方法——异常类型与消息两条路径逐字一致。
- **Rationale**: 课件"复用 16 节那套校验""同一异常、同一消息"。现有 `ProfileLoader.loadOne` 是日志+跳过、不抛异常（`ProfileLoader.java:83-95`），运行时注册需要可抛形态，故校验逻辑必须共享、呈现方式各异。`ProfileLoaderTest` 只断言跳过/注册行为、不断言日志文本（已核实），重构安全。
- **Alternatives**: scanner 里复制一份校验（两份实现必然漂移，拒）；registry 不校验由调用方校验（ProfileRegistryRuntimeTest 要求 register 本身报错，拒）。

## D5 ProfileRegistry：同步保序 Map + register/remove/exists

- **Decision**: 内部 `LinkedHashMap` 保留（保住既有插入序输出契约），新增 `register(Profile)`（先 `ProfileValidator.validate` 再 put）、`remove(String)`、`exists(String)`；全部 public 方法 `synchronized`。
- **Rationale**: 课件字面"改成可变并发 Map"的意图是线程安全可变；`synchronized` + 保序 Map 同时满足线程安全与既有有序输出（`all()` 被 `AgentScheduler.registerAll` 与列表端点消费），比 `ConcurrentHashMap` 行为变化更小。既有构造器签名不动。
- **Alternatives**: `ConcurrentHashMap`（丢插入序，列表输出变序，拒）；`ConcurrentSkipListMap`（变字典序，仍变序，拒）。

## D6 AgentScheduler：循环体抽出 `registerProfile(Profile)`，句柄表沿用 cronFutures

- **Decision**: 把 `registerAll()` 内"逐 Profile 逐规则：buildTrigger→store.register→catalog.put→安装 future→登记 id"的循环体抽为 public `registerProfile(Profile)`；`registerAll` 保持"先取消旧安装清表→逐 profile registerProfile→定义消失清扫"语义。课件要求的"Map<String, ScheduledFuture<?>> 句柄表"已由 011 的 `cronFutures`（`AgentScheduler.java:77`）承担，不新建第二张表。
- **Rationale**: 课件写于 011 之前，011 已交付句柄表；重复造一张表只会引入双份真相。
- **Alternatives**: 新建独立句柄表（与 cronFutures 双真相，拒）。

## D7 启动扫描装配：scanner 普通 Bean，定时注册经可选 scheduler

- **Decision**: 新增 `AgentDirectoryScanner`（`core.agent`，纯 POJO）：`scan(Path agentsDir)` 逐子目录 load→deriveProfile→validator 校验→`registry.register`→（构造时供入了 `AgentScheduler` 且有 schedules 时）`scheduler.registerProfile(profile)`。`CoreEngineConfiguration` 新增 `agentDirectoryScanner` @Bean：依赖 `ProfileRegistry`、全局 provider 名集合、`@Qualifier("toolTable") ObjectProvider`（告警用工具名集合）、`ObjectProvider<AgentScheduler>`（chat 模式无此 Bean，为 null 则跳过定时注册）。Bean 方法内执行扫描。
- **Rationale**: `toolTable` 是普通 @Bean（`ToolConfiguration.java:101`），依赖注入保证其先于 scanner 就绪；scanner 是普通 Bean，其实例化早于 `schedulerRegistrar` 的 `afterSingletonsInstantiated` 回调，故 scanner 先注册、registerAll 后清表重建（`store.register` 幂等保 enabled，无重复触发）。chat 模式无 scheduler Bean 时 Agent 仍进注册表、定时跳过（该模式定时注册数本就恒为零，011 语义）。
- **Alternatives**: scanner 也做成 SmartInitializingSingleton（顺序需额外编排，普通 Bean 已天然早于回调，拒）；scanner 不经 scheduler 而全靠 registerAll（课件 2.1 明确"有 schedules 的再交给 AgentScheduler"，且 AgentScanRegisterTest 要测这一环，拒）。

## D8 未注册能力告警：仅在工具表可得时判定

- **Decision**: scanner 构造接受"已注册工具名集合"（装配方从 toolTable 取 keySet；取不到 toolTable 时传 `null` 表示无法判定，跳过工具告警）。frontmatter `tools` 中不在集合内的名字逐个记 warn 日志（点名能力名与 Agent 名），不阻断注册。
- **Rationale**: 课件校验条："tools 里引用底座未注册的能力 → 加载告警"。core 单测环境无 tool 模块时 toolTable 缺失，空集合会全量误报，null=未知才诚实。
- **Alternatives**: 缺省空表照告警（无 tool 模块环境全误报，拒）；告警升级为报错（课件原文是"告警"，拒）。

## D9 同名冲突默认：记错误日志并跳过派生注册

- **Decision**: scanner 注册前 `registry.exists(name)` 为 true（手写或先扫描的同名）时，记错误日志点名并跳过该目录，不覆盖既有注册项。
- **Rationale**: 课件把"Agent 同名冲突策略"列为扩展阶段，本节只需一个不静默覆盖的诚实默认；`exists()` 顺带成为该判断的支点。
- **Alternatives**: 后者覆盖前者（静默覆盖不可审计，拒）。

## D10 示例 Agent 双落位：gitignored 工作区 + specs 评审副本

- **Decision**: 示例 `daily-reconcile/`（课件 §1.3–1.4 全文四件套）落 `.oryxos/agents/daily-reconcile/` 供本地手工验证；同内容副本落 `specs/012-agent-directory/samples/daily-reconcile/` 入库评审。harness 测试一律用 `@TempDir` 自建夹具，不依赖 repo 内样本。
- **Rationale**: `.oryxos/` 整目录被 `.gitignore:50` 排除，课件要求"由 spec-kit 产出、作手动路径参照物"——入库副本保证评审可见，工作区副本保证手工路径可跑。
- **Alternatives**: 只放 .oryxos（不入库无法评审，拒）；放 examples/ 顶级目录（新增对外目录概念，拒）。

## D11 无新对外概念清单核对

- **Decision**: 本节新增 public 类型仅 `AgentLoader`、`AgentDefinition`、`AgentDirectoryScanner`（core.agent 新包）及四个既有类的加法/改造方法（`register/remove/exists`、`registerProfile`、ContextLoader promptFile 语义）——全部在课件"本节交付物"清单内。无新配置键、无新表、无新 REST 端点、无新 Profile 字段、无新 Maven 模块、无新第三方依赖。工作区子目录字面量 `agents/` 与主文件名 `AGENT.md` 是课件定义的作者面字面量。
- **Rationale**: H1 门禁逐项对照课件交付物清单后确认无越界项。
- **Alternatives**: 无。
