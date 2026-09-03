# Implementation Plan: 可切换的三后端长期记忆

**Branch**: `codex/007-memory-backends` | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

**Input**: `specs/007-memory-backends/spec.md`，含 2 项归档处理与历史保留决议。

**Status**: **DESIGN COMPLETE — 追加范围已获用户批准，Phase 0/1 设计完成，可进入 tasks；尚未实现或通过运行验收。**

**实施进度（2026-08-31）**：用户已确认进入implement；下文保留计划阶段的设计描述。当前实际完成项以[tasks](tasks.md)和[acceptance](acceptance.md)为准，不把单个故事完成当作007封板。

**实施门禁发现（2026-08-31）**：US1/US2已完成本地验收；固定Mem0 1.0.11依赖扫描命中CVE-2026-7597，R1未通过。T024准备完成后暂停，等待用户决议安全基线（源版本/官方补丁回移与扫描处置），不自动升级至不同提炼实现、不添加忽略项。详情与官方来源见acceptance.md。

**已批准恢复（2026-08-31）**：用户批准最小官方补丁回移，保留1.0.11算法；采用诚实标识的`1.0.11+oryx.1`受控wheel和来源绑定的扫描处置，细则见[SDK安全回移契约](contracts/sdk-security-backport.md)。T076–T079先于剩余暂存任务执行。用户同时改为主模型执行所有回归。

**T033检查点（2026-09-01）**：U001远端NUL边界已同步并回归，T033显式隔离PG fixture、合成数据与故障注入点完成，主模型离线单测332项通过，当时进度37/79。缺测试PG环境会明确失败；该检查点尚未运行真实PG。详见acceptance.md后续记录。

**PG环境尝试（2026-09-01）**：用户要求继续后已尝试启动本机Docker Desktop；官方`pgvector/pgvector:0.8.6-pg17`候选已核对，但Desktop 4.82在Windows本地Unix-socket初始化阶段崩溃，未到镜像拉取或容器启动。已恢复原Docker设置/临时目录并停止进程；T034–T035仍等待显式隔离PG，不用现有PG14或假库替代。

**Docker升级结果（2026-09-01）**：经用户明确授权，官方签名安装包已将Desktop原地升级到4.89.0/build238018，未卸载/重置Docker数据。该版本仍因本机inbox WSL不可用、无发行版及Unix-socket无法重命名而不能启动Engine；同版本Hyper-V切换未应用。下一步需单独授权更新/安装WSL或提供外部测试PG，进度保持37/79。

**WSL更新结果（2026-09-01）**：经用户明确授权，`wsl --update`成功升级到2.7.12.0/kernel6.18.33.2-2且未要求重启；Docker随后能创建后端但Engine因`HCS_E_HYPERV_NOT_INSTALLED`停止，要求`wsl --install --no-distribution`修复虚拟机平台注册。该系统组件安装尚未获单独授权，T034–T035继续暂停。

**独立Java分支（2026-09-01）**：用户进一步明确跳过PG/T045、继续其他内容且不得关机重启，批准顺序例外。T054/T059已完成并修绿T053：受限异常只带固定Code/UUID、UNKNOWN必有编号、Adapter不可重试映射、中断不覆盖已验证失败；T056完成ASCII/punycode HTTPS origin、规范token/UUID和期限条件绑定。进度44/79；T045/T051仍保持未完成，T058仍受T051阻塞。

**PG/T045恢复（2026-09-01）**：用户明确要求执行此前跳过的PG/T045且仍不得关机重启。WSL2路径继续受HCS门禁后，使用不依赖Hyper-V的WSL1隔离Ubuntu 24.04，从PGDG安装精确PostgreSQL 17.11与pgvector 0.8.6；仅loopback一次性数据库、非superuser、确认串与数据库comment全部通过T033真实fixture。T045按点名顺序例外完成：严格持久receipt解析、固定错误模板、Bearer摘要绑定及scope无关snapshot/scope绑定cursor分类型HMAC均有冻结黄金向量；T034–T044仍未完成。当前45/79，真实事务、HTTP服务、模型与整体R门禁继续待实施。

**T034/T035测试先行（2026-09-01）**：用户要求进入T034/T035后，主模型在真实隔离PG上建立14项事务与7项查询契约。生产侧仅增加计划文件的最小接口骨架并明确抛出未实现，不提前实施T036–T042。21项均稳定红于T036迁移入口，0 skip/收集错误；既有Python unit 351项保持通过。当前47/79，下一步T036显式迁移，不能把红灯测试计作事务运行通过。

**T036显式迁移（2026-09-02）**：新增版本化`001_initial.sql`及只创建全新schema的迁移器。schema comment固定协议/schema版本/embedding维度；已有schema只做只读精确校验，维度、表/列/类型/约束/索引/触发器/函数/权限任一不符都拒绝，不自动修补。五表、外键/check/index、vector(D)、历史UPDATE/DELETE触发阻断、PUBLIC及默认函数权限收紧均已在真实PG通过5项；完整前沿推进为5绿20红，既有unit 351项通过。当前48/79，业务事务与查询仍未实现。

**T037操作登记与租约（2026-09-02）**：OperationStore在独立事务中用数据库同一时刻写原文、hash、RECEIVED及+30秒deadline，并幂等创建revision=0 namespace；重复ID只读原状态，不刷新期限，不同hash/raw固定冲突。claim以单条带行锁的条件UPDATE仅让未过期RECEIVED取得规范owner和当前baseline revision；登记/抢占后的故障点位于提交后，状态仍可查询且不重抢。5项真实PG测试通过，前沿10绿17红，unit 351项通过；当前49/79，终态业务写仍归T038/T041。

**T038原子提交（2026-09-02）**：新增只读REPEATABLE READ快照；短写事务锁operation/namespace，验证owner、数据库期限与baseline后，先模拟完整动作序列、生成实际版本链及成功receipt并做1MiB预算，再写versions/current/revision/COMMITTED。CORE ADD、ARCHIVAL ADD/多步UPDATE/DELETE/NOOP、历史/当前/receipt/提交前故障回滚、stale CAS与提交后应答丢失均通过；确定失败回滚后以独立事务写匹配FAILED。15项真实PG通过，前沿25绿9红，unit351通过；当前50/79，持久审计/恢复/查询仍待后续。

**T039持久调用审计（2026-09-02）**：CallAuditStore同时适配CallAuditor字典入口和显式参数，数据库时钟独立提交STARTED后才允许I/O；终态仅从STARTED条件更新为COMPLETED/FAILED，usage未提供保持NULL，不伪造0，响应/错误严格限型限额。开始故障零I/O，结束故障让RunContext锁存AUDIT_FAILURE并由T038阻止业务提交。3项真实PG通过，前沿28绿8红，unit351通过；当前51/79，UNKNOWN恢复归T041。

**T040服务组合（2026-09-02）**：OperationService统一登记/租约/总期限；CORE生成单一原文ADD且不打开归档快照，ARCHIVAL只在T038只读快照内调用固定SDK stage，RECALL委托T042只读处理器。已有RUNNING/终态直接返回，绝不再次stage；Engine fatal写固定FAILED且不commit。5项单测通过，完整unit356项通过；当前52/79，PG前沿保持28绿8红。

**T041恢复（2026-09-02）**：恢复事务以行锁+条件更新把过期RECEIVED/RUNNING写成完整ABORTED凭据，再把这些操作关联的STARTED调用标UNKNOWN；重复恢复为0，不延长期限、不重推理、不覆盖COMMITTED。晚owner由T038 fence拒绝。2项真实PG通过，前沿30绿7红，unit356通过；当前53/79。

**T042查询（2026-09-02）**：按versions在revision R选每个memory最后版本并排除DELETE；CORE按created revision/id全量keyset分页，ARCHIVAL取最新100后稳定升序。RECALL只查current ARCHIVAL，以pgvector精确余弦排序top20，再按完整receipt实际JSON预算取最长前缀。7项及PG前沿37/37通过，unit356通过；当前54/79，待T043硬门禁收口。

**T043事务硬门禁（2026-09-02）**：主模型对隔离非superuser PG17.11/pgvector0.8.6执行fixture+事务/故障/恢复+查询39项，全部通过；新增组件重建后COMMITTED/current/versions持久回读。R2暂存与事务机制通过，但不代表API、部署安全、真实模型或007完成。当前55/79。

**T044 API测试先行（2026-09-02）**：建立FastAPI五端点、全认证、字段/256KiB/1MiB、五种receipt、snapshot跨scope/cursor拒绝、矛盾成功与OUTCOME_UNKNOWN单次PUT、禁history/reset/config/docs共25项；最小ApiRuntime无路由骨架产生21行为红灯，4项负向通过，无收集或外网错误。unit356通过；当前56/79，待T046/T047修绿。

**T046快照服务（2026-09-02）**：SnapshotService以当前revision签发scope无关HMAC snapshot；page重构R时点，cursor绑定完整snapshot摘要/scope/固定排序/最后键并继承≤300秒期限。存储页后用真实token重算完整JSON，必要时缩短完整前缀；拒绝过期、越workspace/scope、type混用和不前进。4项单测、查询7项PG回归及unit360通过；当前57/79，待T047路由。

**T047五路由（2026-09-02）**：FastAPI仅暴露caps、PUT/GET operation、POST snapshot、GET entries，禁docs/openapi/history/reset/config。全路由Bearer/workspace绑定，raw body先做256KiB与严格判别，receipt DTO/身份/状态严格序列化，固定错误和1MiB响应；startup_check及固定能力声明在注册前验证。API25/25、完整真实集成64/64、unit360/360通过；当前58/79，仍待部署R1/R3证据。

## Summary

**T049/T080检查点（2026-09-03）**：用户更新系统后Docker Engine29.7.2恢复。构建改为固定Python镜像内按完整hash requirements安装依赖、校验源码清单；兼容Docker29的OCI manifest ID并实际回填digest。T080修复vector初始化、nullable usage、replayed及持久失败/冲突映射、状态恢复和请求/分页预算；377单测、65 PG集成及8真实容器用例通过，实际64/80。R1镜像门禁仍失败，不更改锁定运行版本或豁免告警；T049继续未完成。T061仍缺获准内网模型与embedding，.env仅有云Provider/NVD key。详情见acceptance及image-security-review。

**T051/T057/T058/T060检查点（2026-09-02）**：Java真实Store+HTTPS对端68项及装配/文案/既有本地集成合计93项通过；完成固定协议、单次SAVE派发/原ID确认、双scope完整分页、过期检查和boot Guard→Sandbox接线，实际63/79。复用父POM已锁定的spotbugs-annotations 4.10.3（provided）记录唯一受guard保护的URLConnection边界说明，不增加运行依赖或模块。T049仍受Docker/WSL2阻塞，T061缺获准内网模型；PMD6数据流诊断与无跳过全仓门禁仍归R5，不能启用或归档Mem0。

006 的 `3d60ee0` 文件基线不变。007 默认 Markdown、可选 SQLite 和自托管 Mem0，保留核心端口及 9 个 Maven 模块。Mem0 显式归档保存须自动提炼/合并/替换，原始输入与旧版本须持久保全，常规召回排除历史。

本地后端可在既有边界内设计；但仅增加 Java REST 客户端直连原版 Mem0 server，不能兑现核心全量、历史先保全和内部调用审计。具体源码证据见 [research.md](research.md)。

用户已明确批准保持 007 一个 feature，增加与 Mem0 同部署的受控 Python 适配组件，以及 tool/boot 中必需的 HTTP 白名单接线。设计不再依赖原版 REST 的缺失能力：固定 SDK 只在请求级暂存对象上生成变更，外部 PostgreSQL 单事务提交有效状态、追加历史和操作凭据。核心保存完全绕过推理。

本轮产出是实现契约，不是宣称新组件已经存在。按 [data-model](data-model.md)、[Java契约](contracts/memory-contract.md)、[适配协议](contracts/mem0-adapter-api.md) 和 [验证指南](quickstart.md) 生成 tasks 后，仍须用户确认实施。真实安全/部署证据是启用与封板门禁，不能以设计通过替代。

## Technical Context

**Language/Version**: OryxOS JDK 21（本机21.0.11）；外部适配 Python 3.12.14。Python组件是获准的可选外部交付物，不是第十个Maven模块。

**Primary Dependencies**: 本轮离线依赖树核验：Spring Boot 3.5.16、Spring AI 1.1.8、Spring Web 6.2.19、Spring Data JPA 3.5.13、Hibernate 6.6.53.Final、SQLite JDBC 3.53.2.1。不升级现有 BOM。memory 尚无 Spring Web 编译依赖；后续若使用 RestClient，应显式加入现有 BOM 管理的 spring-web。

**External Dependencies**: mem0ai 1.0.11 / SHA 144627c4ce5bc4db6acac17cbd158065f2b27a8d；FastAPI 0.141.1、uvicorn 0.52.4、psycopg 3.3.4；PostgreSQL 17.11 + pgvector 0.8.6。开发测试工具基线 pytest 9.1.1、pip-audit 2.10.1。官方版本已核验，但完整依赖解析/uv.lock、SDK文件摘要与镜像digest须在首批实现任务生成并扫描；未通过时禁止启用，不自动换到ADD-only版。

**Storage**: Session/审计三表与本地memory_entries留在SQLite；外部PG五表为memory_namespaces、memory_operations、memory_current、memory_versions、memory_call_audits。原文先登记；current/versions/COMMITTED receipt在单事务提交，历史不删。没有跨SQLite/PG分布式事务，不把Java事后审计当写前保障。

**Testing**: JUnit Jupiter 5.12.2、真实文件 SQLite、ApplicationContextRunner、MockWebServer 4.12.0。后续测试必须经过真实适配器，远端传输替身不代替真实自托管冒烟。本轮未运行应用测试或完整 verify。

PG integration fixture只接受独立的三项测试环境变量，并在任何schema清理前核对数据库名/确认串、PG 17.11、pgvector 0.8.6、非superuser和`ORYXOS_DISPOSABLE_TEST_DATABASE` comment；远端连接要求verify-full。缺环境或不匹配以错误退出，不skip成通过、不复用运行时DATABASE_URL、不创建默认数据库。清理目标固定为已核验测试库内`oryx_memory` schema。

**Target Platform**: 项目既定 Linux 部署和 Windows 开发环境；外部记忆服务不成为默认本地路径的启动条件。

**Project Type**: 9 模块 Java 单体能力增量；已批准将可选外部适配纳入 integrations/mem0-adapter，不新增 Maven 模块。

**Performance Goals**: 延续项目目标，不静默截断核心。Java连接3s、读取30s、默认逻辑总期限40s；服务处理30s。snapshot有效300s，页≤100条/实际JSON1MiB；归档窗口100条、召回≤20条并按字节返回完整前缀。生成facts≤64、动作/暂存变更≤128、单条≤32KiB、内部模型请求/响应≤1MiB；成功receipt预算在COMMITTED前校验。超限明确拒绝，不隐式丢弃SAVE动作。

**Constraints**: 同步阻塞、核心公共签名不变、唯一后端、未选后端无记忆数据访问、显式建表、错误可观测、无默认外发。历史保全与有效状态可读同时满足才可成功。

**Scale/Scope**: 工作区级共享而非多租户；Markdown 归档最近 4000 Java char、SQLite 最近 100 条、Mem0 核心全量。仍包含 US1–US4，Mem0 P2 不代表可以跳过交付。

## Constitution Check

*GATE: Phase 0 前核对范围；研究发现无法满足的原则立即阻断 Phase 1。*

| 原则/门禁 | Phase 0 结果 |
|---|---|
| I 自实现 ReAct、II 禁自动 Tool、III 显式 Provider | 设计通过：控制面不变，外部仅记忆处理 |
| IV Profile/上下文边界 | 设计通过：scope与工作区身份不派生第二套Profile |
| V 审计落库 | 设计通过：Java旧审计保留；服务独立持久调用审计，提交前检查；待故障验收 |
| VI 安全与数据边界 | 设计通过：缺guard/Secret失败、HTTPS/白名单、强workspace绑定、禁遥测云回退；待实际部署证据 |
| VII 同步执行 | 设计通过：同步边界+虚拟线程限时等待，无固定池/CF编排/并行Tool |
| VIII 状态外置、Memory完整性 | 设计通过：请求暂存、PG原子提交、追加历史、revision快照全量；待真实事务验证 |
| 模块与职责审批 | 用户已批准外部适配及HTTP白名单；9模块不变，事实源/AGENTS同步 |
| 006 稳定提交 | `3d60ee0`已归档；本轮未改006或应用代码 |

原版直连的设计失败没有被豁免，而是由获准的适配机制替代。宪法v3.0.0已允许受控外部记忆，本次不修改原则或降版本。表中“设计通过”仅表示有闭合方案与可追踪验收，不能推断实现、安全扫描或真实运行已通过。

### G1–G7 处置

| 编号 | 当前结果 | 后续要求 |
|---|---|---|
| G1 端口与Store | 设计闭合 | memory-contract第1/2节，保留void/String和旧构造入口 |
| G2 SQLite | 设计闭合 | data-model第2节，字段专用UTC毫秒转换、instr查询、初始化排序 |
| G3 协议/分页/scope | 自有协议已定 | snapshot无scope、cursor绑scope/具体snapshot，revision-keyset分页；操作凭据匹配身份/hash |
| G4 历史/可见性/幂等 | 暂存机制已核验并定稿 | data-model第3–5节，单事务与owner/revision检查；先做机制harness |
| G5 安全/无环 | 接线定稿 | MemoryOutboundGuard→boot→HttpWhitelistSandbox，无memory→tool边 |
| G6 配置/身份 | 契约定稿 | JSON绑定/origin格式与重复策略、非nil UUID/规范Secret、预算和失败确认状态机明确 |
| G7 下游/审计 | 设计闭合；运行证据待实施 | 明确外部Secret、允许origin、审计表和失败闭锁，运行启用受R门禁 |

## Project Structure

### Documentation (this feature)

已有 spec.md、checklists/requirements.md、plan.md、research.md、data-model.md、contracts/memory-contract.md、contracts/mem0-adapter-api.md、quickstart.md；[tasks.md](tasks.md) 已由speckit-tasks生成，75项待一致性分析及实施确认，尚未执行。

### Source Code (repository root)

以下为确定的计划落位，不是本轮新增代码：

- `oryxos-core/src/main/java/com/oryxos/core/memory/`：保留 MemoryService/MemoryScope，仅后续同步查询 Javadoc。
- `oryxos-memory/src/main/java/com/oryxos/memory/`：保留 LongTermMemory，计划增加 Store 与三适配器、改造门面和条件配置。
- `oryxos-storage/src/main/java/com/oryxos/storage/memory/`：计划 MemoryEntry/Repository，四字段不增加工作区或租户列。
- `oryxos-storage/src/main/resources/db/schema.sql`：计划追加幂等 Memory DDL，旧三表不重建。
- `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/`：HttpWhitelistSandbox，仅HTTP提前，其余默认拒绝。AnnotatedToolAdapter精确映射MemoryOperationException。
- `oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java`：保留已验证的不可重试失败与操作编号，不通过清除中断标志继续工作；不改公共签名。
- `oryxos-boot/`：MemoryOutboundConfiguration组合guard与Sandbox；集成测试覆盖全部后端。
- `integrations/mem0-adapter/`：计划pyproject.toml、uv.lock、compose.yaml、Dockerfile、src/oryx_mem0/、migrations/、tests/；可选独立交付，不新增Maven模块。

外部src/oryx_mem0内分app/contracts、engine/staging、storage、audit、security职责；StagedMemory使用固定私有SDK调用点，SQLiteManager/原版Memory构造器不得初始化真实业务存储。SDK导入产生的非业务配置固定在受控MEM0_DIR。

**Structure Decision**: 不把 Sandbox 搬入 core，不新增 Maven 模块；默认本地运行不依赖外部适配。

## Complexity Tracking

| 已批准复杂度 | 为什么需要 | 较简单替代不能满足的原因 |
|---|---|---|
| 外部 Python 受控 Mem0 适配 | 完整读取、硬隔离、先保全、操作状态与内部审计 | 事后补写无法恢复已丢失旧值，数量上限不能证明全量 |
| 007 提前接入 HTTP 白名单和 boot 窄接线 | 可执行外部能力必须有真实校验 | 当前全拒绝；空 guard 与循环依赖均不可接受 |
| 安全锁定与真实环境验证 | 算法语义匹配不代表部署安全 | 不能使用开放依赖范围、默认云配置或假 Store 作为证据 |

用户已批准前两项范围追加；第三项仍为必需验收，不是可绕过的例外。适配只做协议/事务兼容，不另造Agent循环或向量检索算法。

## Delivery Sequence & Gates

### US3a 实施细化

T025–T030仅建立请求级暂存结果，不产生COMMITTED凭据。内部RunContext固定工作区/操作UUID、scope、快照revision、向量维度与单调总期限；失败先锁存固定分类，再抛出安全异常。StagedMemory只调用固定同步私有入口；核心范围拒绝进入推理，后续由事务协调器原文保存。

暂存Vector/History不持有业务写仓储。只读Snapshot按工作区/ARCHIVAL/revision绑定，get/search结果二次校验归属；search接受只读overlay，由后续PG查询适配器按现成pgvector计算，不在引擎自建向量算法。ADD/UPDATE/DELETE与历史/SDK事件对账，NONE仅有已核对的metadata-only证据，不刷新有效内容或recency。

内部Change.baseline_version_id仅标识原快照版本，不冒充持久history的previous_version_id；同操作多步UPDATE/DELETE的实际版本前驱由T038短事务按action_index接续。暂存结果不是持久化成功，快照连接生命周期归事务协调器。

内部模型调用采用Python标准库同步HTTPS，固定origin、标准TLS、禁代理和重定向。总期限覆盖DNS、连接、头和正文；DNS助手不携带正文，超期不继续连接，连接看门狗只负责关闭资源，不新增后台推理队列。受控包装在开始审计成功后才I/O，结束审计失败立即fatal；仅持久审计适配在后续T039实现。本阶段的审计替身不算真实PG证据。

1. US1：Store、选择配置、默认Markdown兼容；先验证006断言和未选后端隔离。
2. US2：SQLite实体/转换/仓储/幂等DDL，真实事务和重启验收。
3. US3a：建立锁文件与固定SDK暂存harness；校验NONE直写、吞错、审计失败和所有写入拦截。机制验证未通过，不继续完整远端适配。
4. US3b：五表迁移、operation状态、原子提交、快照/查询、HTTPS/认证；Python真实PG故障集成。
5. US3c：Java Mem0MemoryStore、HTTP白名单/guard和受限错误；已派发SAVE未取得匹配持久终态一律查原ID或报UNKNOWN，不被畸形200/5xx等诊断覆盖，不重放。
6. US4：共同/差异契约、6向切换、跨重启、前序回归、真实内网模型与全路径安全；显式integration加Java/Python/镜像完整门禁。

US4验收定义伴随各步骤，不最后补测试。代码、测试与最终回归均由主模型执行（2026-08-31最新确认），不再调度Spark。

### US3b 配置与共享协议基础

T031–T032只推进可离线验证的启动边界。Settings冻结显式配置，绑定与origin坏项整份拒绝，Secret不进入repr或异常；bootstrap先验证TLS/受控目录，再关闭遥测并导入已锁定SDK，不连接业务库或模型。数据库URI、路径和TLS加载细化见适配协议§7.2，真实出口/存储仍受后续门禁约束。

共享contracts.py提供精确NUL分隔请求hash、冻结身份及分状态receipt、实际UTF-8序列化和提交前预算函数，供T037–T042调用；pending/失败/成功字段不可混用，不接受暂存结果冒充持久凭据。远端正文/查询/生成文本拒绝U+0000，但hash字段间NUL分隔符不变。DTO构造不证明持久化成功；事务代码尚未实现，T045仍负责路由边界、HMAC及跨语言黄金向量。

| 运行/封板门禁 | 必须取得的证据 |
|---|---|
| R1 依赖可重复与安全 | 完整锁图、固定SDK文件摘要、镜像digest；pip-audit/镜像扫描，无未处置门禁问题 |
| R2 暂存与事务机制 | SDK吞错不能提交、历史失败不覆盖、冲突不重推理、崩溃/响应丢失正确恢复 |
| R3 安全接线 | 缺Secret/guard拒绝，TLS/域名/跨scope负例，默认遥测/云出口被阻断 |
| R4 真实后端验收 | 内网LLM/embedding完整审计，golden事实集、核心全量、历史留存、6向切换 |
| R5 全仓质量 | 保留006断言，显式integration、无跳插件的mvn clean verify、Python和镜像证据 |

这些证据当前均未声明通过。计划闭合后可生成tasks并按用户确认实现验证；未通过R门禁不得实际启用Mem0部署、归档或宣称007完成。若固定SDK安全/行为无法满足，应重新决议，禁止静默降级规格。
