# Tasks: 可切换的三后端长期记忆

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[Java 契约](contracts/memory-contract.md)、[适配协议](contracts/mem0-adapter-api.md)、[quickstart.md](quickstart.md)

**Branch**: `codex/007-memory-backends` | **Created**: 2026-08-30

**Status**: Done — 2026-09-04 归档。85/85 完成（含 T076–T085 补救）；R1–R5 全通过：镜像/依赖逐项处置门禁 passed、无跳插件 mvn clean verify SUCCESS、显式 integration SUCCESS、Python 377/377 与真实集成 74/74、真实本地模型黄金集通过。

**Tests**: 规格与宪法要求自动验收，因此测试不是可省项。测试先于对应实现；必要接口骨架就位后，应观察行为断言失败，而不是只记录缺类导致的编译错误。不得删断言、Disabled、降低阈值或用假 Store 替代真实适配器。

## 执行约定

- 下列路径均相对仓库根目录 `D:/project/AI-Coding/OryxOs`；路径是计划修改对象，不代表已经存在。禁止改写 `specs/006-memory/`，保留 006 的公共入口和回归断言。
- `[P]` 仅表示所列前置完成后可以并行的不同文件工作，不授权自动创建新任务/代理。未标记任务按阶段顺序推进；同一文件的跨阶段任务串行执行。
- US 编号/优先级沿用 spec。US4 虽为 P1，但最终闭环依赖 US3；按 plan 拓扑顺序 US1→US2→US3→US4 收口，US4 的共同断言提前在基础和各故事内建立。
- 按2026-08-31最新确认，代码、测试用例及包括T073在内的所有回归均由主模型执行，不再调度Spark。
- 2026-09-01用户明确要求跳过PG/T045、继续其他内容且不得关机重启；因此允许执行已有独立契约/红灯覆盖的T054/T056/T059，不回填或伪造T045/T051，后续Store/整体门禁仍按原依赖验收。
- 2026-09-01用户随后明确要求执行PG/T045；在不关机重启的约束下，以WSL1隔离Ubuntu建立精确PG 17.11/pgvector 0.8.6一次性测试库并通过T033真实fixture。T045按用户点名作为T044前的顺序例外完成；T034–T044仍保持未完成，不能把协议单测或fixture绿灯当作事务/HTTP服务验收。
- 注释与错误/审计消息遵守仓库简体中文约定，标识符/测试方法保持英文；Specify CLI继续锁0.14.2，九模块和已批准公开契约不变，不新增社区extension。
- 每个任务只有实现和对应验证完成才勾选；长日志放 `.verification/007-memory-backends/`，稳定结论、命令、结果、源码版本及证据路径写入 `specs/007-memory-backends/acceptance.md`。缺真实环境/凭证时标未执行，不能拿 skip/fake 当通过。
- 每故事结束做一致性审查并按 AGENTS 本地提交稳定边界；不得 stage 其他工作或推送。用户已于2026-08-31确认实施，提交只包含本feature范围。
- 运行验证只用显式配置的隔离测试库和合成数据；Secret 不入仓库/日志，禁止改生产库、默认云外发、删除已有卷或运行 package.sh。

## Phase 1: Setup

**Purpose**: 建立可追踪的基线和证据边界，不重建已有九模块工程。

- [X] T001 在 `specs/007-memory-backends/acceptance.md` 建立执行台账：记录 006 基线 `3d60ee0`、九模块/公共签名/既有测试清单及当前未提交改动归属；将 R1–R5 初始状态全部记为未执行，保留前序证据但不算作 007 验收。
- [X] T002 更新 `.gitignore`，仅补充外部组件的 `.venv/`、Python缓存、pytest缓存、测试证据/本地数据库与Secret排除；确保 `integrations/mem0-adapter/uv.lock` 和无敏感信息的构建清单可跟踪，不扩大忽略范围掩盖业务源文件。

## Phase 2: Foundational

**Purpose**: 共享端口与共同验收骨架；T003–T004 完成后才进入故事。不能因抽象测试类存在就宣称三个后端已覆盖。

- [X] T003 新建 `oryxos-memory/src/main/java/com/oryxos/memory/LongTermMemoryStore.java`，只定义 append(String,MemoryScope)、load()、recall(String)；标注核心全量、归档窗口不删数据、查询排除核心和失败语义，不改变 core MemoryService/MemoryScope 签名。
- [X] T004 新建 `oryxos-memory/src/test/java/com/oryxos/memory/AbstractMemoryStoreContractTest.java`，提供真实适配器工厂与隔离fixture，定义核心完整、scope/空输入、写后可见、重开读取与失败断言；关键词/语义、4000字符/100条/远端分页用后端专属断言，不强求相同命中或顺序。（依赖 T003）

## Phase 3: US1 — 默认兼容（P1，MVP）

**Goal**: 不设置新配置和任何 MEM0 环境变量，现有用户仍可保存、回忆和跨重启使用文件记忆。

**Independent Test**: 旧工作区→默认启动→核心/归档保存→新会话/重启→读取；保留 3999/4000/4001 边界及旧格式断言，远端访问为零。

### Tests first

- [X] T005 [P] [US1] 新建 `oryxos-memory/src/test/java/com/oryxos/memory/MarkdownMemoryStoreContractTest.java`，绑定真实 LongTermMemory 和临时工作区，继承共同契约并覆盖旧格式/缺区块、全量归档字面检索、只裁剪注入视图及用户资产只读。（依赖 T004）
- [X] T006 [P] [US1] 新建 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryConfigurationTest.java`，覆盖默认后端、完全缺少MEM0变量、非法选择、唯一Store、自定义MemoryService/LongTermMemory回退装配；未实现但已选后端必须明确失败，不降级Markdown。（依赖 T004）
- [X] T007 [P] [US1] 扩展 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryServiceImplTest.java` 和 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryToolsTest.java`，锁住旧构造入口、void/String签名、scope兼容、既有成功文本及Prompt历史角色/顺序，不删原断言。（依赖 T004）

### Implementation & checkpoint

- [X] T008 [US1] 新建 `oryxos-memory/src/main/java/com/oryxos/memory/MarkdownMemoryStore.java`，委托现有LongTermMemory，复用路径锁、原子写、读取修复与4000 Java char裁剪，不另写解析器或内容缓存。（依赖 T005）
- [X] T009 [US1] 新建 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryProperties.java`，仅绑定/校验backend三值，缺省markdown；禁止此全局类型提前绑定Mem0必填Secret。（依赖 T006）
- [X] T010 [US1] 修改 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryServiceImpl.java` 以Store为内部依赖，保留LongTermMemory构造入口；同步 `oryxos-core/src/main/java/com/oryxos/core/memory/MemoryService.java` 查询Javadoc，不改变任何核心签名。（依赖 T007、T008）
- [X] T011 [US1] 修改 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java`，条件创建Markdown Store及唯一门面、保留已有backoff；非法/缺失所选实现报错，不构造其他后端或触发其网络/Secret读取。（依赖 T009、T010）
- [X] T012 [US1] 在 `oryxos-boot/src/test/java/com/oryxos/boot/MemorySystemIntegrationTest.java` 保留并验证006重启/新Session/实际SQLite工具审计链路，运行US1单测和该显式integration，证据写入 `specs/007-memory-backends/acceptance.md`。（依赖 T011）
- [X] T013 [US1] 对照规格/计划/任务与US1实现完成故事级一致性审查，在 `specs/007-memory-backends/acceptance.md` 记录结论和测试版本；问题修复后按AGENTS提交该故事稳定边界，不能将未完成后端标完成。（依赖 T012）

## Phase 4: US2 — SQLite 原文后端（P1）

**Goal**: 使用本地结构化记忆，稳定保存、查询和重启恢复，不依赖远端。

**Independent Test**: 两个独立工作区/DB；保存核心与101条归档，重开真实SQLite仍完整；注入最新100条，旧匹配仍能查询；默认后端无Memory行访问。

### Tests first

- [X] T014 [P] [US2] 新建 `oryxos-storage/src/test/java/com/oryxos/storage/memory/UtcMillisInstantConverterTest.java`，验证固定UTC毫秒串、`.000Z`及非零毫秒排序、时区不敏感和非法表示拒绝。（依赖 T013）
- [X] T015 [P] [US2] 新建 `oryxos-storage/src/test/java/com/oryxos/storage/memory/MemoryEntryRepositoryTest.java`，用真实文件SQLite验证四字段、typeof(created_at)=text、时间/id排序、参数化instr大小写/中文/引号/百分号/下划线，以及旧库增表/重复执行/错误结构检测。（依赖 T013）
- [X] T016 [P] [US2] 新建 `oryxos-memory/src/test/java/com/oryxos/memory/SqliteMemoryStoreContractTest.java`，绑定真实仓储；覆盖99/100/101窗口、窗口外查询、核心排除、并发已确认保存、重开回读、两个DB隔离与明确失败。（依赖 T013、T004）

### Implementation & checkpoint

- [X] T017 [US2] 新建 `oryxos-storage/src/main/java/com/oryxos/storage/memory/UtcMillisInstantConverter.java`，字段专用autoApply=false，Instant截毫秒后appendInstant(3)，读取须规范回转，不影响旧Session/审计时间字段。（依赖 T014）
- [X] T018 [US2] 新建 `oryxos-storage/src/main/java/com/oryxos/storage/memory/MemoryEntry.java`、`oryxos-storage/src/main/java/com/oryxos/storage/memory/MemoryEntryRepository.java`，并在 `oryxos-storage/src/main/resources/db/schema.sql` 追加四字段表与idx_memory_scope；保存原文、原三表不重建，查询严格按data-model的instr和时间/id规则。（依赖 T015、T017）
- [X] T019 [US2] 新建 `oryxos-memory/src/main/java/com/oryxos/memory/SqliteMemoryStore.java`，保存事务提交后才成功；load核心全量+归档最近100条，recall扫描全部归档；不UPDATE/DELETE历史、不缓存唯一状态。（依赖 T016、T018）
- [X] T020 [US2] 在 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java` 接入SQLite条件Store及SQL初始化后的结构/工作区校验，禁用后端不得查数据行；不把Repository已创建当成schema已就绪。（依赖 T019）
- [X] T021 [US2] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendFixture.java` 和 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendSystemIntegrationTest.java` 的本地部分，覆盖SQLite新Session/重启、旧三表数据保留、MD↔SQLite不搬数据；fixture复用真实能力和合成LLM响应。（依赖 T020）
- [X] T022 [US2] 执行仓储/Memory模块测试及T021显式integration，在 `specs/007-memory-backends/acceptance.md` 记录事务回读、默认路径无Memory行查询及两库隔离证据，不能只凭内存实体断言持久化。（依赖 T021）
- [X] T023 [US2] 完成US2故事级一致性审查、修复和本地稳定提交，将验收与提交号写入 `specs/007-memory-backends/acceptance.md`；不改006验收历史。（依赖 T022）

## Phase 5: US3 — 受控 Mem0（P2，不能省略）

**Goal**: 明确启用自托管服务后，归档自动提炼/合并/替换，原始输入和旧版本留存；核心完整、未知结果不重放、每次出站受控。

**Independent Test**: 固定SDK真实暂存引擎+真实PG+真实Java适配器跑通保存、查询、重启和历史追溯；再以获准内网模型做最小真实冒烟。模型替身可验证错误分支，但不代替最终真实模型证据。

### US3a: 固定依赖与暂存机制

- [X] T024 [US3] 建立 `integrations/mem0-adapter/pyproject.toml`、`integrations/mem0-adapter/.python-version`、`integrations/mem0-adapter/src/oryx_mem0/__init__.py` 并生成 `integrations/mem0-adapter/uv.lock`；固定Python3.12.14、Mem01.0.11源码SHA144627c4ce5bc4db6acac17cbd158065f2b27a8d及plan依赖；在 `integrations/mem0-adapter/tests/conftest.py` 确保SDK导入前设置临时MEM0_DIR和禁遥测、拒绝默认网络，区分unit/integration，不能靠已缓存导入掩盖副作用。（依赖 T023）
- [X] T025 [P] [US3] 新建 `integrations/mem0-adapter/tests/unit/test_staged_engine.py` 和 `integrations/mem0-adapter/tests/fixtures/sdk_fingerprint.json`，验证固定SDK摘要、无真实存储/核心推理、全部写方法暂存、合法NONE→NOOP、坏JSON/吞错/越scope拒绝；补facts第65项、动作第129项、生成内容超32KiB及转义膨胀负例，均须提交前fatal。（依赖 T024、T079）
- [X] T026 [P] [US3] 新建 `integrations/mem0-adapter/tests/unit/test_providers.py`，用受控传输/审计替身测试开始审计失败不发请求、结束失败置fatal、Provider坏结果不伪装空事实、TLS/origin/期限和Secret脱敏；验证内部请求/响应实际字节超1MiB在I/O/解析边界拒绝。（依赖 T024、T079）
- [X] T027 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/engine/staging.py`：快照search/get加overlay；所有写/history仅暂存，reset/delete_col拒绝；校验归属、生成条目32KiB和暂存变更128项上限，超限置fatal，不能透传业务库写入。（依赖 T025）
- [X] T028 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/engine/providers.py` 和 `integrations/mem0-adapter/src/oryx_mem0/audit/calls.py`，受控审计/LLM/embedding严格校验；内部请求/响应各1MiB、facts≤64、动作含NONE≤128，超限先fatal不伪装NOOP；禁隐式云/proxy/OpenRouter，不依赖SDK callback。（依赖 T026）
- [X] T029 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/engine/staged_memory.py`，不调用原版构造器/from_config/public add，只注入已核验字段后执行固定归档调用点；逐项对账动作/暂存/history/结果，对NONE使用其特例证据，核心走原文事务。（依赖 T027、T028）
- [X] T030 [US3] **机制硬门禁**：运行T025/T026实际固定SDK测试和锁定依赖安全检查，在 `specs/007-memory-backends/acceptance.md` 记录SDK摘要、零真实写入/零默认网络、NONE成功与fatal负例；失败不得进入US3b，不升级到ADD-only、不换假Store。（依赖 T029）

### US3b: 服务端事务、协议与安全

- [X] T031 [US3] 新建 `integrations/mem0-adapter/tests/unit/test_settings_security.py`，按协议§7.1覆盖JSON数组形状、未知字段、64hex摘要/非nil UUID、重复key拒绝、多key同workspace允许、origin规范化后重复/CSV/路径拒绝、token及cursor密钥格式；并验证必填/维度/TLS、import禁遥测、MEM0_DIR和错误不回显Secret。配套 `tests/unit/test_contracts.py` 验证T032共享hash/DTO/序列化基础。（依赖 T030）
- [X] T032 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/settings.py`、`integrations/mem0-adapter/src/oryx_mem0/bootstrap.py` 的严格配置及导入安全，并先在 `integrations/mem0-adapter/src/oryx_mem0/contracts.py` 实现共享请求hash、receipt DTO、序列化/预算基础函数供后续存储使用；路由适配留T045。坏项整体拒绝，不跳过/覆盖配置。（依赖 T031）
- [X] T033 [US3] 建立 `integrations/mem0-adapter/tests/integration/conftest.py` 的显式隔离PG/pgvector fixture、合成数据和故障注入点；仅连接明确提供的测试库，禁止生产库/默认外部模型，不因缺环境把integration记为通过。（依赖 T030）
- [X] T034 [P] [US3] 新建 `integrations/mem0-adapter/tests/integration/test_transactions.py`，验证五表/原文/幂等/RECEIVED期限/owner/CAS、历史和审计失败回滚、提交未知/崩溃；增加生成条目/完整序列化receipt超限必须在COMMITTED前回滚，以及FAILED/ABORTED匹配凭据和memory_effects_applied=false。（依赖 T033；当前30项全部通过）
- [X] T035 [P] [US3] 新建 `integrations/mem0-adapter/tests/integration/test_queries.py`，覆盖同一snapshot读CORE/ARCHIVAL、核心超过100条、字节分页、100条窗口、最多20条召回；用引号/反斜杠/Unicode验证整个JSON预算、最长完整前缀及truncated_by_bytes、不截正文/不伪装空结果，并保留历史/并发/身份隔离用例。（依赖 T033；7项全部通过）
- [X] T036 [US3] 编写 `integrations/mem0-adapter/migrations/001_initial.sql` 与 `integrations/mem0-adapter/src/oryx_mem0/storage/migrations.py`，显式建立oryx_memory五表、关联/索引/最小权限；验证schema版本/维度，历史禁止UPDATE/DELETE，未知结构拒绝，不自动修改业务库。（依赖 T034、T032）
- [X] T037 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/storage/operations.py`，登记原文/hash/不可延长deadline并持久化RECEIVED；仅未过期记录取得唯一owner，重复请求读同状态、不同hash冲突，提供查询和确定终态更新。（依赖 T036；终态写入由T038/T041完成，本任务已提供严格查询解析）
- [X] T038 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/storage/memories.py`，主库REPEATABLE READ快照；最终短事务校验owner/期限/baseline、生成完整receipt并以发送序列化规则验证1MiB及生成条目预算后，再原子提交versions/current/revision/receipt；历史或预算失败全回滚。（依赖 T037）
- [X] T039 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/storage/call_audits.py`，为受控wrapper提供持久STARTED/COMPLETED/FAILED/UNKNOWN、usage与workspace/operation关联；失败不回传任意正文、未提供token为空，审计失败禁止SAVE提交。（依赖 T036、T028；UNKNOWN恢复写入归T041）
- [X] T040 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/services/operations.py`，组合CORE原文追加、ARCHIVAL暂存推理和RECALL只读路径；合法NOOP保留原文/receipt而不刷新条目内容或recency；fatal、冲突和未知COMMIT都不能报告完整成功或重推理。（依赖 T029、T038、T039；T042提供实际查询处理器）
- [X] T041 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/storage/recovery.py`，在启动/状态读取时用行锁处理过期RECEIVED/RUNNING→ABORTED和未完成调用→UNKNOWN；不延长期限、不重新运行SDK、不覆盖可能已COMMITTED的结果，晚到owner无法提交。（依赖 T037、T038）
- [X] T042 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/storage/queries.py`，按versions重构当前快照有效条目；核心全量/归档100条，RECALL仅当前ARCHIVAL按score DESC/id ASC取top20，再按实际receipt字节取完整前缀、记录计数/字节缩减标记；拒绝非法向量和不可容纳单项，不做ANN或历史检索。（依赖 T035、T038、T039）
- [X] T043 [US3] **事务硬门禁**：执行真实PG的T034/T035及恢复故障测试，在 `specs/007-memory-backends/acceptance.md` 记录current/history/receipt同生共死、原文保留、revision冲突与重启证据；禁止以SQLite或纯内存假库替代。（依赖 T040、T041、T042）
- [X] T044 [US3] 新建 `integrations/mem0-adapter/tests/integration/test_api.py`，覆盖五端点、认证/字段/大小和全部receipt状态；验证snapshot跨scope复用而cursor跨scope/snapshot/type/排序拒绝；注入提交后畸形200/错误hash/缺history标记/5xx及lookup失败，固定SAVE未知优先规则；无历史/重置/配置入口。（依赖 T043；25项全部通过）
- [X] T045 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/contracts.py`、`integrations/mem0-adapter/src/oryx_mem0/security.py` 及 `integrations/mem0-adapter/tests/fixtures/protocol-v1.json`：严格判别体/终态凭据、字段间NUL-hash向量、远端正文NUL拒绝、§7.1绑定/origin格式、分类型HMAC令牌、实际JSON字节预算及固定错误模板，不回显Secret。（依赖 T044；用户点名批准顺序例外；后续T044/T047已补齐验证）
- [X] T046 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/services/snapshots.py`：snapshot只绑定workspace/revision/nonce/期限且跨scope复用；cursor另绑具体snapshot摘要/scope/排序/最后键。每页≤100条且完整序列化≤1MiB，计数/complete正确，禁止令牌混用、越scope游标、旧revision请求或不前进页。（依赖 T042、T045）
- [X] T047 [US3] 实现 `integrations/mem0-adapter/src/oryx_mem0/app.py`，仅注册协议五路由，全部认证，组合配置/schema检查、操作/恢复/快照服务；caps只报告实际构建/协议兼容性，不代替安全证据。（依赖 T040、T041、T045、T046）
- [X] T048 [US3] 编写 `integrations/mem0-adapter/Dockerfile`、`integrations/mem0-adapter/compose.yaml` 与 `integrations/mem0-adapter/build-manifest.json`，固定源码/完整锁图/镜像digest，显式可选profile、受控TLS/Secret/持久卷/内网origin和受限角色；不使用原版server默认部署，不默认启动或绑定公网。（依赖 T047、T024）
- [X] T049 [US3]（第二轮镜像安全门禁通过，见 acceptance 2026-09-03） 运行真实适配HTTP/PG集成及构建依赖/镜像检查，记录 `specs/007-memory-backends/acceptance.md` 的协议版本、schema与拒绝证据；未满足R1/R3只可留在隔离测试，不能启用真实业务环境。（依赖 T048）

### US3c: Java 适配与工具链

- [X] T050 [US3] 在 `oryxos-memory/pom.xml` 显式加入现有BOM管理的spring-web及已锁定MockWebServer测试依赖，并新建 `oryxos-memory/src/test/java/com/oryxos/memory/HttpsFixture.java` 提供临时测试CA/证书（使用JDK工具和已有依赖），不加入生产trust-all开关或未批准库。（依赖 T030）
- [X] T051 [P] [US3] 新建 `oryxos-memory/src/test/java/com/oryxos/memory/Mem0MemoryStoreContractTest.java`，真实Store+HTTPS替身覆盖共同契约/hash、同snapshot双scope/错误cursor、JSON转义字节预算和完整前缀；SAVE派发后畸形/超限200、hash/身份不符、缺receipt/history、5xx及GET拒绝均须查原ID或报UNKNOWN，不重PUT；保留caps/慢响应/无降级。（依赖 T050、T045；67项先行，66行为红灯/1通过；T057/T058及过期补例后68/68通过）
- [X] T052 [P] [US3] 新建 `oryxos-tool/src/test/java/com/oryxos/tool/sandbox/HttpWhitelistSandboxTest.java` 并扩展 `oryxos-tool/src/test/java/com/oryxos/tool/ToolConfigurationTest.java`，验证host精确匹配/IDN、空名单和非法URI、非HTTP动作拒绝以及用户已有Sandbox优先。（依赖 T050）
- [X] T053 [P] [US3] 新建 `oryxos-memory/src/test/java/com/oryxos/memory/MemoryOperationExceptionTest.java`，扩展 `oryxos-tool/src/test/java/com/oryxos/tool/AnnotatedToolAdapterTest.java` 和 `oryxos-core/src/test/java/com/oryxos/core/react/ToolExecutorTest.java`，锁住错误分类/UUID/不重试、未知结果与中断明细、非Memory异常仍脱敏。（依赖 T050）
- [X] T054 [US3] 新建 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryOutboundGuard.java` 与 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryOperationException.java`，只开放契约中的检查及固定分类/UUID，不接收远端任意message；OUTCOME_UNKNOWN必须带编号。（依赖 T051、T053；用户批准跳过T051顺序实现，T051本身仍未完成）
- [X] T055 [US3] 实现 `oryxos-tool/src/main/java/com/oryxos/tool/sandbox/HttpWhitelistSandbox.java` 并修改 `oryxos-tool/src/main/java/com/oryxos/tool/ToolConfiguration.java` 默认接线，复用http.allowed_domains，只提前HTTP能力，其余动作拒绝且不覆盖自定义Sandbox。（依赖 T052）
- [X] T056 [US3] 新建 `oryxos-memory/src/main/java/com/oryxos/memory/Mem0Properties.java` 并修改 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java` 条件绑定，校验HTTPS origin、规范非nil UUID、43字符规范token和3s/30s/40s预算；默认本地绝不解析Mem0变量。（依赖 T054、T006）
- [X] T057 [US3] 实现包内 `oryxos-memory/src/main/java/com/oryxos/memory/Mem0HttpTransport.java` 和 `oryxos-memory/src/main/java/com/oryxos/memory/Mem0Protocol.java`：最终URI先guard，禁重定向/代理继承，限时关闭资源，校验实际字节/各状态身份hash/失败无业务效果字段。向Store保留派发阶段及诊断，不能用传输层错误覆盖已派发SAVE的未知结果。（依赖 T054、T056、T050）
- [X] T058 [US3] 实现 `oryxos-memory/src/main/java/com/oryxos/memory/Mem0MemoryStore.java`，固定oryx-memory-v1；SAVE在发送前设置DISPATCHED，此后只用匹配终态确认，否则剩余期限内查原ID、最终UNKNOWN且不重PUT；独立RECALL/load按只读失败处理。校验同snapshot双scope完整页及召回预算标记，不返回部分核心/伪记忆，不缓存或降级。（依赖 T057、T051）
- [X] T059 [US3] 修改 `oryxos-tool/src/main/java/com/oryxos/tool/AnnotatedToolAdapter.java` 精确映射受限异常，并修改 `oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java` 保留校验后的非重试失败与中断标志；不改ToolResult/审计接口或透传任意异常。（依赖 T053、T054）
- [X] T060 [US3] 新建 `oryxos-boot/src/main/java/com/oryxos/boot/MemoryOutboundConfiguration.java` 组合Guard→Sandbox，完成 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryConfiguration.java` 与 `oryxos-memory/src/main/java/com/oryxos/memory/MemoryTools.java` 接线/包内确认文案；在 `oryxos-boot/src/main/resources/application.yaml` 给出不会影响本地默认的配置入口，缺guard/caps不兼容必须失败。（依赖 T055、T058、T059）
- [X] T061 [US3]（真实本地模型最小冒烟通过，见 acceptance 2026-09-03） 执行Java单元/真实适配器契约及最小获准自托管冒烟，在 `integrations/mem0-adapter/tests/integration/test_runtime_smoke.py` 覆盖真实内网模型保存→原子历史→新会话/重启→回忆，记录 `specs/007-memory-backends/acceptance.md`；环境缺失时该项保持未完成，不能用传输替身填绿。（依赖 T049、T060）
- [X] T062 [US3] 完成US3故事级一致性审查及稳定提交，在 `specs/007-memory-backends/acceptance.md` 明确源码/SDK/锁图/部署版本、已通过证据与剩余全矩阵验收；保留原版缺口说明，不用caps声明或Java绿灯替代Python/真实服务证据。（依赖 T061）

## Phase 6: US4 — 切换、追溯与整体验收（P1）

**Goal**: 使用者知道当前后端、切换结果及操作成败；三个后端在真实运行中保持兼容与可追溯。

**Independent Test**: 对每种后端独立保存→新会话/重启→回忆，再执行全部6个有向切换与回切；检查旧数据不变、未选后端零访问、工具审计与服务内部证据分别成立。

- [X] T063 [US4] 扩展 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendFixture.java` 和 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendSystemIntegrationTest.java` 到三后端，复用真实Store/Session/Tool链路，覆盖Prompt相对顺序、角色/历史上限、无空段、既有构造入口及配置重启生效。（依赖 T062、T021）
- [X] T064 [P] [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendSwitchIntegrationTest.java`，覆盖6向切换、空目标提示、回切恢复、两工作区隔离、MD/SQLite无MEM0变量启动及未选后端网络/行访问计数，不隐式搬运或清库。（依赖 T063）
- [X] T065 [P] [US4] 新建 `oryxos-boot/src/test/java/com/oryxos/boot/MemoryBackendAuditIntegrationTest.java`，验证保存/回忆成功及拒绝/超时/未知各经统一工具链；SQLite failed+固定分类/UUID、内部PG操作/calls/history分别取证，审计故障可观测且不触发重放。（依赖 T063）
- [X] T066 [P] [US4] 新建 `integrations/mem0-adapter/tests/integration/test_live_models.py` 及 `integrations/mem0-adapter/tests/fixtures/memory-golden.json`，在明确获准的合成语料上验证真实内网模型提炼、合并、替换、合法空事实和同义召回；未提供真实环境保持该验收未通过，不删断言换绿。（依赖 T062）
- [X] T067 [P] [US4] 编写 `integrations/mem0-adapter/README.md`，说明部署/Secret/hash绑定/TLS/维度、版本与镜像锁、NOOP/未知结果的含义、历史只读核对、操作ID查询和安全停止；不新增Agent历史工具、管理UI或自动迁移/清理命令。（依赖 T062）
- [X] T068 [US4] 执行三后端integration/黄金集及真实出口检查，在 `specs/007-memory-backends/acceptance.md` 记录R2–R4矩阵、跨重启历史、无默认遥测/云流量、完整模型/embedding/存储/日志路径和失败证据；不上传业务数据或未脱敏配置。（依赖 T064、T065、T066）
- [X] T069 [US4] 用实际可用命令回核并修订 `specs/007-memory-backends/quickstart.md` 与 `README.md` 的007使用说明，逐项对应默认后端、重启选择、不同窗口/检索、数据不迁移与运行门禁；准确区分已实现、已验证及待封板。（依赖 T067、T068）
- [X] T070 [US4] 完成US4故事级一致性审查和本地稳定提交，结果写入 `specs/007-memory-backends/acceptance.md`；所有6向切换和独立后端验收都齐全后才标该故事完成，R5最终全仓验证仍单独执行。（依赖 T069）

## Phase 7: Polish & Cross-Cutting Gates

**Purpose**: 封板前统一工程化和最终证据，不用源码存在性替代运行通过。

- [X] T071 更新 `.github/workflows/ci.yml`，保持Java门禁并加入外部组件frozen-lock单测/隔离PG集成/依赖安全验证；清楚区分快门禁和封板全量门禁，无NVD key时的跳过路径不能算完整verify。真实模型测试仅用显式获准环境，CI不默认访问云端或注入业务Secret。（依赖 T070）
- [X] T072 回核 `integrations/mem0-adapter/build-manifest.json`、`integrations/mem0-adapter/uv.lock` 与 `integrations/mem0-adapter/Dockerfile` 的源码/依赖/镜像摘要，执行获准的本地或内网镜像扫描、记录工具版本/规则库时间/结果到 `specs/007-memory-backends/acceptance.md`；不上传私有镜像，缺工具或未处置问题则R1不通过。（依赖 T071）
- [X] T073 按用户最新要求由主模型执行最终回归：`mvn clean verify`（不跳插件）、显式Java integration、Python unit/integration、来源绑定的完整依赖审计及quickstart验收；将命令、源码/镜像版本和实际结果写入 `acceptance.md`，保留原始扫描和长日志，不以补丁验证替代真实运行门禁。（依赖T072）
- [X] T074 由主模型完成最终实现一致性审查，逐项核对 `specs/007-memory-backends/spec.md`、`specs/007-memory-backends/plan.md`、`specs/007-memory-backends/tasks.md` 与实际代码/证据；发现缺口追加可追踪补救任务，不删旧断言或直接改勾选，修复后重跑受影响门禁。（依赖 T073）
- [X] T075 仅在R1–R5全通过后收口 `specs/007-memory-backends/acceptance.md`、`specs/007-memory-backends/spec.md` 和 `docs/decisions/007-memory-backends-scope.md` 状态，记录各故事提交与剩余人工项，完成本地归档提交；只提交本feature明确变更，不自动push，未验收项存在则不得标007完成。（依赖 T074）

## Dependencies & Execution Order

```text
Setup T001–T002
  → Foundation T003–T004
  → US1 T005–T013（MVP）
  → US2 T014–T023
  → US3a T024–T030（固定SDK暂存硬门禁）
  → US3b T031–T049（T043真实事务硬门禁）
  → US3c T050–T062（Java安全接线、最小真实冒烟）
  → US4 T063–T070（6向切换/追溯/真实黄金集）
  → Polish T071–T075（R1–R5、最终审查、归档）
```

编号是默认串行执行顺序。US3功能本身不依赖SQLite算法，但为避免共享MemoryConfiguration与验收台账并行编辑，默认先完成US2。US3c的测试/叶子类型可在其显式前置满足后提前，不能越过T030机制门禁部署远端实现；最终接线/验收仍按T062汇合。

US4是跨后端验收，优先级P1不改变其依赖US3的事实；其共同断言已由T004及各故事测试先行。新失败不允许通过仅完成较低风险故事就宣布整个feature完成。

### Parallel examples

| 范围 | 前置 | 可以并行 | 不可并行的共享点 |
|---|---|---|---|
| US1 | T004 | T005、T006、T007分别写测试 | T010/T011接线与T013台账收口 |
| US2 | T013 | T014、T015、T016测试 | T018 schema、T020配置、T021fixture |
| US3a | T024 | T025 SDK机制测试、T026 wrapper测试 | T029引擎组合与T030门禁 |
| US3b | T033 | T034事务测试、T035查询测试 | T038/T040事务组合与共享测试库写入 |
| US3c | T050且T045 | T051、T052、T053测试编写 | T056/T060共改配置，T059改执行链 |
| US4 | T063且T062 | T064、T065、T066、T067不同文件 | 运行时各用独立工作区/库；T068–T070串行收口 |

表中并行仅指文件工作；共享外部服务、数据库、证据文件或系统TLS配置时必须隔离，不能同时改同一台账。

## Requirement / Contract Coverage

| 需求 | 主要任务 | 可验证结果 |
|---|---|---|
| FR-001–FR-004 | T005–T012、T020、T056、T060、T064 | 默认兼容、唯一选择、缺MEM0变量不影响本地、未选零访问 |
| FR-005 | T016、T021、T034–T047、T064 | 两个本地DB/远端凭证工作区隔离 |
| FR-006–FR-008 | T005、T007–T010、T016–T019、T025–T043、T046、T058 | 核心不改、合法归档处理、窗口不删历史 |
| FR-009–FR-010 | T015–T019、T035、T042、T058、T066 | 本地字面包含、远端只召回有效归档 |
| FR-011–FR-013 | T004、T012、T016–T022、T034–T043、T051、T058、T061–T068 | 成功后可读、真实提交、重启恢复、并发不丢 |
| FR-014 | T015、T018、T020–T022 | 旧库与重复启动安全，不重建原三表 |
| FR-015–FR-016 | T006–T012、T044–T047、T051–T060、T063、T065 | 公共入口和Prompt兼容，错误/无结果可区分 |
| FR-017 | T021、T064、T067–T069 | 全6向切换不搬运/删除/双写 |
| FR-018–FR-020 | T026、T028、T031–T032、T044–T049、T052–T060、T068 | Secret/认证/HTTPS/白名单与完整出口受控 |
| FR-021 | T034、T037–T041、T044–T047、T051、T053–T059、T065 | 同ID去重、未知不重放、期限/崩溃/fence |
| FR-022 | T025–T030、T034–T043、T061、T066、T068 | 原始输入及旧版本保全、合法NOOP、历史失败不覆盖 |
| FR-023 | T026、T028、T039、T053、T059、T065、T068 | SQLite工具审计与PG内部审计分别可追溯 |
| FR-024 | T067、T069、T075 | 使用说明与实际实现/验收状态一致 |

| 成功标准 | 验收任务 |
|---|---|
| SC-001 默认完整流程 | T005–T013 |
| SC-002 写后可见与重启 | T016、T021–T022、T034–T043、T061、T063、T068 |
| SC-003 窗口不删数据 | T005、T016、T035、T042、T046、T051 |
| SC-004 正确召回与历史排除 | T015–T019、T025–T030、T035、T066、T068 |
| SC-005 六向切换 | T064、T068 |
| SC-006 禁用/拒绝零访问 | T006、T026、T031–T032、T052–T060、T064、T068 |
| SC-007 明确失败与不重放 | T034–T043、T044、T051–T061、T065 |
| SC-008 审计与历史完整 | T026、T039、T043、T065、T068 |
| SC-009 前序兼容与可操作说明 | T012、T063、T067–T069、T073–T074 |

| 设计对象 | 实现 / 验收任务 |
|---|---|
| Store及旧构造入口 | T003–T012、T019、T058 |
| SQLite四字段/UTC转换/instr | T014–T022 |
| PG五表、版本链及权限 | T034–T039、T043 |
| RECEIVED期限、owner、revision、原子receipt | T034、T037–T041、T043 |
| GET capabilities / PUT operation / GET operation | T044–T045、T047、T051、T058 |
| POST snapshot / GET entries | T035、T042、T044、T046–T047、T051、T058 |
| Guard→Sandbox→受限异常→Tool失败 | T051–T060、T065 |
| R1依赖和镜像 | T024、T030、T048–T049、T071–T073 |
| R2事务机制 | T025–T030、T034–T043、T068 |
| R3安全接线 | T026、T031–T032、T044–T060、T068 |
| R4真实后端 | T061、T064–T068、T073 |
| R5全仓质量 | T071、T073–T075 |

## Implementation Strategy

1. **最小可交付增量**：T001–T013，仅US1默认Markdown兼容；这不是007全功能完成。
2. **本地增量**：US2真实SQLite验证后建立稳定提交，保留默认用户体验。
3. **高风险先验证机制**：US3a只做受控暂存与必需包装；T030未通过不展开服务。PG阶段再用T043阻断不可靠的历史/状态实现。
4. **接线与全面验证分开**：先证明真实适配器/安全/最小冒烟，再做US4全部切换和真实黄金集；最后冻结候选源码/镜像并由主模型执行回归。

## 已批准补救任务（T076–T079）

来源：用户批准回移官方安全补丁并核验后继续，见[安全回移契约](contracts/sdk-security-backport.md)。以下属于US3与R1，执行位置为T024之后、T025之前；不重编号原任务，不把原始扫描发现删除或伪装成未发生。

- [X] T076 [US3] 同步四份事实源、AGENTS、plan/contracts与当前回归模型决议；在 `integrations/mem0-adapter/vendor/sdk-lock.json`、`vendor/upstream-sdk-files.json` 固定来源、官方补丁及SDK代码集合，保留许可证。（依赖T024）
- [X] T077 [US3] 先写 `tests/unit/test_sdk_build.py`、`tests/unit/test_sdk_security.py` 红灯，再实现 `scripts/sdk_build.py` 与 `patches/CVE-2026-7597.patch`，产出 `vendor/mem0ai-1.0.11+oryx.1-py3-none-any.whl`；验证只有FAISS源码变化、危险pickle拒绝、合法旧格式/JSON、篡改失败及可重复构建。（依赖T076）
- [X] T078 [US3] 更新 `pyproject.toml`、`uv.lock` 和指纹/来源测试；实现并测试 `scripts/audit_dependencies.py` 的来源绑定处置：完整依赖清单、原始报告保留、只标记已证实修复的目标CVE，其他/未知/缺失/扫描失败仍阻断。（依赖T077）
- [X] T079 [US3] 主模型执行补丁测试、重复构建、frozen同步、完整依赖扫描与源验证，记录 `acceptance.md`；只读一致性复审通过后解除此CVE的实施阻断，继续T025，不能将T030或R1–R5整体标完成。（依赖T078）
5. **运行失败不能变文档成功**：没有真实环境、依赖安全不通过、快照不完整、历史或审计不可靠均保持对应任务未完成；只能记录阻碍并请求所需输入，不删需求或悄悄换方案。

## T049真实部署发现的补救（2026-09-03）

- [X] T080 [US3] 修复真实容器检查发现的接线缺口：新PG库显式启用固定vector扩展；允许审计usage中合法NULL字段；登记结果携带真实replayed标记；实际服务异常优先读取匹配持久终态，冲突/失败/未知结果用固定类型和状态映射；状态读取触发已批准恢复，并补齐请求读取与分页信封预算。真实HTTPS/SDK/PG回归、旧单测/集成及源码绑定镜像重建通过后才完成，不以此替代镜像漏洞与真实模型门禁。（依赖T049检查发现；不重编号原79项）

## T062一致性审查发现的补救（2026-09-03）

- [X] T081 [US3] capabilities 补齐契约要求的构建版本与固定限制：`runtime.py` 从镜像内 source-manifest 实际字节计算 build_version，`app.py` 校验形态而非整体相等；Java `Mem0Protocol` 同步要求 64-hex build_version 与六项固定 limits，双侧 fixture 与测试同步，不得放松未知字段拒绝。（依赖T062审查发现）
- [X] T082 [US3] RECALL 读取改为只读 REPEATABLE READ 单快照（revision 与 items 一致），`complete_recall` 将 revision 校验从等于 baseline 改为单调不后退（与 Java 侧 minimum/max 语义一致），补并发 SAVE 期间 RECALL 不伪失败及钳位 score 用例。（依赖T062审查发现）
- [X] T083 [US3] `runtime.py` 的 `PgReadonlySnapshot` 分别返回真实 created_at/updated_at，不再以 updated_at 同时填充；核验推理读快照 revision 与 RunContext baseline 的绑定由提交 CAS 兜底并补测试。（依赖T062审查发现）
- [X] T084 [US3] 补提交阶段真实连接丢失测试：pg_terminate_backend 终止 RUNNING/提交连接后，操作不得出现假 COMMITTED 或假 FAILED，恢复路径产生 ABORTED/可查询终态，重 PUT 幂等返回。（依赖T062审查发现）
- [X] T085 [US3] 收窄 `tests/conftest.py` 的 `inprocess_asgi`：fixture 期间仅放行 loopback 连接，非回环目标仍拒绝；验证 API 测试不依赖非回环连接，不删除网络拒绝断言。（依赖T062审查发现）

## Generation Check & Next Step

### 已纳入的实现前修复

| 审查项 | 文档规则 | 对应验证任务 |
|---|---|---|
| I1 快照/scope不一致 | snapshot无scope，cursor绑scope与具体snapshot | T035、T044、T045、T046、T051 |
| A1 保存无效响应含义 | 已派发SAVE只以匹配持久终态确认，失败观测不能覆盖UNKNOWN | T034、T044、T051、T057、T058 |
| A2 输出预算未闭合 | 生成结果/receipt提交前限额，按实际JSON字节分页/返回前缀 | T025–T028、T034、T035、T038、T042、T044、T045、T051 |
| A3 绑定配置缺格式 | JSON schema、规范化、重复策略与Secret格式固定 | T031、T032、T045、T056、T067 |

这里记录的是获准文档修复，不是运行通过；实际执行状态见上方逐项勾选与acceptance.md。

生成时校验：75项全部未勾选；US1=9、US2=10、US3=39、US4=8，准备/基础/收尾=9；17项标记[P]。24条FR、两份契约、全部持久模型及R1–R5均有任务映射。本文的生成核对不是正式speckit-analyze，也不是运行验收。

生成阶段未实施应用代码、测试、服务启动或提交。实现前的 `/speckit-analyze` 及I1/A1/A2/A3修复复审已完成，用户已确认进入实现。新公共类型、表、配置、路径超出已批准契约时，先报告并同步设计，不在实施中自行扩张。
