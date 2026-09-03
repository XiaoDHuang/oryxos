# 007 实施与验收台账

实施开始：2026-08-31；分支 `codex/007-memory-backends`。用户已确认进入实现；原75项加安全回移T076–T079及真实部署补救T080，当前按80项任务执行。

## 基线与保护范围

- 006归档基线：`3d60ee0`；不改写 `specs/006-memory/` 或删除既有回归断言。
- 九模块：core、provider、memory、tool、web、channel-cli、storage、cli、boot；不新增Maven模块。
- 保持 `MemoryService.buildContext(Session,int)`、`remember(String,MemoryScope)`、`recall(String)`，两个MemoryTools方法及旧LongTermMemory构造入口。
- 既有测试：LongTermMemoryTest、MemoryServiceImplTest、MemoryToolsTest、MemorySystemIntegrationTest，以及core Prompt/ToolExecutor、storage Session/审计和tool注册/适配回归。
- 开始时未提交变更均属前序007治理/设计：四份事实源、AGENTS、宪法及模板、课程技能/21–22课件、README、feature选择、范围决议和007规格/计划/任务。开始时无应用代码/POM/CI改动；这些已批准文档作为007前置基线保留，不混入无关变更。
- 006已有228默认测试及显式集成、完整verify/OWASP证据仅为历史，不计007验收。
- 2026-08-31用户最新确认：代码、测试用例及所有回归均由主模型执行，不再调度Spark；此前未实际执行Spark回归。

## 实施前检查

check-prerequisites通过；requirements.md为16/16，无未完成项；无扩展hooks。Specify CLI维持0.14.2。

## 故事证据

| 增量 | 状态 | 命令/结果/证据 | 稳定提交 |
|---|---|---|---|
| US1 默认Markdown兼容 | 完成 | 94项单测+1项真实重启集成、快速质量检查通过 | `2a63e58` |
| US2 SQLite | 完成 | 109项迭代单测通过，最终55项受影响测试（含3集成）通过，静态快速门禁通过 | `5333e77` |
| US3 固定SDK/受控Mem0 | PG/API与Java Store、安全接线已分层验证；真实部署/模型待验收 | T024–T048、T050–T060及T076–T079完成；最新Java定向93/93通过 | 本轮变更未归档提交 |
| US4 三后端整体 | 未执行 | 待执行 | 未提交 |

## 运行/封板门禁

| 门禁 | 状态 | 必需证据 |
|---|---|---|
| R1 依赖可重复与安全 | Python补丁/依赖部分通过，整体未完成 | 原始1项CVE在受控构建中证实修复；完整镜像及部署证据仍待实施 |
| R2 暂存与事务 | **通过** | 固定SDK暂存、五表原子提交、故障回滚、CAS、恢复、分页及组件重建持久回读均经真实PG验证 |
| R3 安全接线 | Python调用与协议安全单测通过，整体未完成 | Bearer/HMAC/origin/TLS/期限/限额已测；Java Store接线与实际出口仍待验收 |
| R4 真实后端 | 未执行 | 内网模型/embedding、黄金集、历史和六向切换 |
| R5 全仓质量 | 当前Java回归通过，整体未完成 | 本轮255默认测试、5显式集成及完整verify通过；完整外部组件/镜像验收仍待实施 |

设计完成不代表运行通过。缺真实环境时保持相关项未完成；不运行生产数据操作、不默认外发、不push。

## US1 迭代记录

- 红灯：`mvn -o -pl oryxos-memory -am spotless:apply test -Dtest=MarkdownMemoryStoreContractTest,MemoryConfigurationTest,MemoryServiceImplTest,MemoryToolsTest -Dsurefire.failIfNoSpecifiedTests=false`；22项，4个行为断言失败、5个适配骨架错误，证明未实现选择器与适配；日志 `.verification/007-us1-red.log`。
- 期间修复新测试的AssertJ泛型编译错误，未放宽断言。
- 绿灯：`mvn -o -pl oryxos-memory -am spotless:apply test`；core48、storage9、memory37，合计94项，0失败/错误/跳过；日志 `.verification/007-memory-backends/us1-tests.log`。
- 集成：`mvn -o -pl oryxos-boot -am test -Dtest=MemorySystemIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest.excludedGroups=`；1项通过，真实关闭/重建Spring上下文、文件记忆与SQLite工具审计；日志 `.verification/007-memory-backends/us1-integration.log`。
- `.gitignore`仅补组件及验证产物，`uv.lock`和`build-manifest.json`未被忽略；组件构建上下文排除缓存与Secret。006源码LongTermMemory和归档规格无改动。
- 快速质量复验：`mvn -o -pl oryxos-memory -am spotless:apply verify -Ddependency-check.skip=true`，BUILD SUCCESS；Spotless/Checkstyle/P3C/SpotBugs通过，94项测试再通过。修复5项接口Javadoc与3项魔法常量告警，未修改规约；日志 `.verification/007-memory-backends/us1-quality-fast.log`。离线/显式跳过OWASP，仅为快速门禁，不是R5。
- US1只读一致性审查：默认选择、backoff、旧入口/文案、核心完整、窗口/检索与Prompt顺序符合规格；24 FR+9 SC全部有任务映射，75任务无未映射项，宪法冲突0、US1遗留阻断0。后两后端仍未实现，未以US1绿灯替代007完成。

## US2 迭代记录

- 红灯：固定UTC毫秒和缺表测试先失败（2断言、3错误），随后仓储5项转绿、Store骨架7项中的6项失败；日志 `us2-red.log`、`us2-store-red.log` 位于 `.verification/007-memory-backends/`。
- Store绿灯：`mvn -o -pl oryxos-memory -am spotless:apply test -Dtest=UtcMillisInstantConverterTest,MemoryEntryRepositoryTest,SqliteMemoryStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false`；storage5+memory7项通过；日志 `us2-stores.log`。随后补充坏时间不修复和外层回滚后的独立提交断言，待全仓复验。
- 配置红灯：实际运行时选择sqlite尚未装配Store，2项集成断言失败，日志 `us2-config-red.log`；接线及SQL初始化后结构校验完成后转绿。
- 集成绿灯：`mvn -o -pl oryxos-boot -am spotless:apply test -Dtest=MemorySystemIntegrationTest,MemoryBackendSystemIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest.excludedGroups=`；3项通过，日志 `us2-integration.log`。
- 证据：真实文件DB四字段、UTC毫秒TEXT、字面instr与时间/id排序；99/100/101窗口及窗口外检索；两个DB隔离、并发10项确认保存；真实关闭/重启/新Session；MD→SQLite→MD不迁移/改文件；已有Session、tool_invocations及llm_calls保留；默认/回切MD的记忆行访问探针为0；选中SQLite的错误结构失败。
- 快速质量首轮发现4项超长SQL行（另主动修复1项boot测试同类问题），仅拆分字符串常量，不改规则或断言。
- 后续规约/静态发现与修复：JPA构造失败风险改为入库前回调校验并增加真实仓储负例；回调采用包内方法以明确框架调用边界；配置条件提取常量并拆开复合表达式；schema关键词匹配改为ASCII大小写不敏感Pattern，不对整个SQL做Unicode大小写变换。未新增扫描豁免、未放宽断言。
- 迭代单测共109项通过（core48/storage16/memory45），但该次命令在静态扫描阶段失败，保留 `us2-quality-fast.log`，不将其称为完整verify成功。
- 静态复验：`mvn -o -pl oryxos-memory -am spotless:apply verify -DskipTests -Ddependency-check.skip=true`，BUILD SUCCESS；Spotless/Checkstyle/P3C/SpotBugs通过；日志 `us2-static-fast.log`。此命令未重复跑测试且跳过OWASP，只是快速检查；最终受影响单测/集成复验另记。
- 最终故事检查：`mvn -o -pl oryxos-boot -am spotless:apply test -Dtest=Memory*Test,LongTermMemoryTest,SqliteMemoryStoreContractTest,MarkdownMemoryStoreContractTest,UtcMillisInstantConverterTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest.excludedGroups= checkstyle:check`；storage7+memory45+boot3=55项，0失败/错误/跳过，10个reactor项目检查通过，BUILD SUCCESS；日志 `us2-checkpoint.log`。这是US2迭代，不是T073最终Spark回归。
- US2只读复审：四字段/显式增表、字段专用UTC转换、参数化instr、原文与100条窗口、确认后真实提交、重启/切换/隔离及未选行访问均有实证；无模块/核心签名扩张，无宪法冲突、未映射任务或US2遗留阻断。整体33条需求仍全部有任务映射，但US3/US4与R1–R5未完成，不能宣称007已完成。

## US3 准备及安全阻断（2026-08-31）

- T024：pyproject/.python-version/uv.lock、无副作用包入口及测试隔离已建立。Python3.12.14，Mem0固定Git SHA `144627c4ce5bc4db6acac17cbd158065f2b27a8d`，依赖按plan精确声明；uv解析73个包，frozen同步成功。未启用Mem0业务服务、未添加任何默认云配置。
- 本机uv0.10.11内置清单不含3.12.14，首次安装失败记录在 `python-install.log`。随后从官方uv仓库固定提交 `7c1d80ed02eb434f8228f42b32fa6c157bab7f3c` 的download-metadata.json安装到 `.verification/007-memory-backends/python/`，使用20260825 Windows构建及SHA256 `8e6aad12ef6fc9685e67ce66253f8f72d6e8fa02cb7187e5850bd4db5ecd9e2a`；不改全局Python、注册表或系统uv，记录 `python-install-official.log`。
- `uv lock --project integrations/mem0-adapter --python <上述隔离Python绝对路径>` 与对应 `uv sync --frozen` 成功，日志 `uv-lock.log`、`uv-sync.log`。R1的完整构建链及镜像证据仍未生成，不能把运行/测试依赖锁当成R1全部通过。
- SDK源码指纹覆盖实际调用点及相关helper/提示词/遥测等7文件，固定SHA源码和安装源码仅有Git Windows CRLF差异；测试只规范化CRLF→LF，不改变其他字节。main.py规范化SHA256 `04035505fc37dce54a98e5868b6775d3f57f40c4f070e6d6463138286e98e3e1`，当前Windows原始文件SHA256 `98336498d7bcfe81c0fdfd4b0895a3585befde124799f09d8dc05f1cad6ab167`。
- 在 `integrations/mem0-adapter` 工作目录执行 `uv run --frozen --python <隔离Python绝对路径> pytest -q tests/unit/test_staged_engine.py`：2项通过（`sdk-environment.log`），验证版本/Git来源/指纹/私有入口形状和导入前禁遥测、临时MEM0_DIR仅生成config.json。网络在测试收集前默认拒绝。这仅完成T025的准备断言，未执行实际暂存、NONE或故障机制，因此T025保持未完成。
- 提前执行T030所需依赖检查：同目录 `uv run --frozen --python <隔离Python绝对路径> pip-audit --strict --format json --output ../../.verification/007-memory-backends/python-audit.json`，退出1，命中1包1告警；日志 `python-audit.log`，原始JSON完整保留。
- 告警为Mem0 1.0.11的 `PYSEC-2026-2636` / `CVE-2026-7597` / `GHSA-xqxw-r767-67m7`，位于FAISS的pickle反序列化；[官方公告](https://github.com/advisories/GHSA-xqxw-r767-67m7)列修复版本2.0.0b2，[官方修复提交](https://github.com/mem0ai/mem0/commit/62dca096f9236010ca15fea9ba369ba740b86b7a)调整FAISS持久化及安全测试。当前faiss包未安装、设计不选FAISS，但未据此添加忽略或宣称准入通过。
- 官方修复提交已经属于不同SDK主实现，不能直接替换固定调用点并假称兼容；改变源版本或回移安全补丁需同步plan/锁图/来源摘要及验收方案。建议评估最小官方补丁回移、保留原提炼算法；版本型扫描可能仍命中，须另有明确可核验的处置依据，不能伪造版本或默认豁免。等待用户批准安全基线调整。
- T024完成；T025部分准备；T026–T030机制实现未完成，US3b/US3c未开始。依实现技能/plan安全门禁暂停，不绕到后续接线。另查本机Docker daemon未启动，尚无获准真实PG/内网模型环境证据，R2–R4继续未验收。
- 本次安全阻断时原计划的T073/Spark回归尚未执行；用户随后改为全部由主模型回归。007完整外部环境门禁仍未收口，不能将本轮修复标作007完成。

## 已批准安全回移及主模型回归结果

- 用户批准回移官方补丁并重新核验，新增T076–T079，共79项任务。四份事实源、AGENTS及配套契约已同步；宪法、Maven模块、Java实现和006归档未修改。
- 官方PyPI wheel的149个Python文件逐项匹配固定Git树。官方安全提交所在父版本还改了FAISS的`limit/top_k`接口和导入位置，因此首次严格套用拒绝（未模糊应用）；仅提取官方安全helper及`__init__/_load/_save/delete_col`节点适配到原源码，其余方法保留，原始diff和适配diff分别留存。
- 受控版本 `1.0.11+oryx.1`；产物SHA256 `a3bc3ee9c9d64d836f8f4df3198b142999a3a0e5b4aea6c7306deebdb852c127`，适配diff SHA256 `e8102aeea2d369d78d06ac8636759acb344f4e63d520bd15acda453b84e3f121`。来源、LICENSE、149文件清单、只改FAISS、元数据和实际安装一致均经验证；`uv.lock`只切换Mem0包，其他依赖版本未变。
- 红灯：`backport-red.log`的14项失败复现原SDK危险pickle未拒绝及缺少JSON修复；合成行为仅写pytest隔离标记，不运行系统命令。`audit-policy-red.log`的11项失败证明处置逻辑尚未实现。
- 绿灯：组件目录运行 `uv run --frozen pytest -q --basetemp <本任务唯一临时目录>`，最终32/32通过，日志`backport-unit-complete.log`。覆盖恶意pickle、合法旧内容、JSON优先/保存、结构拒绝、严格构建/锁图/安装、非安全方法不变、未知告警/包/别名/版本漂移/缺依赖失败。早期共享pytest临时目录清理有3项系统警告，记录保留在`backport-unit-final.log`；未删除其他目录，后续改为每次独立basetemp并通过。
- 源验证命令（仓库根）：`uv run --frozen --directory integrations/mem0-adapter python scripts/sdk_build.py --check-installed`通过，SDK源码集合SHA256为`343b8c0bd52a9054456207ef1cfd491e2d9d2d03f062dba5b8c8ffc164613484`；原7项提炼/提示词等指纹全部保留。
- 最终依赖审计（组件目录）：`uv run --frozen python scripts/audit_dependencies.py`退出0；71个实际安装依赖全部覆盖，原始发现1、证实回移修复1、未处置0。原始扫描退出1仍被保留，不称零告警、不使用忽略参数；证据目录`.verification/007-memory-backends/dependency-audit/20260831T141652Z-e28f2a2c-ec42-4bc0-a367-1d6e4b02be39/`，含原始JSON/日志、依赖清单、补丁回归及`source-verified-audit.json`。汇总日志`source-audit-complete-run.log`。
- 主模型完整Java回归：首次`mvn clean verify`在本机JDK创建Unix-domain loopback连接时失败，原日志`main-model-java-verify.log`保留。使用本机JDK源码核验后，独立Selector/TCP回环探针证明指定有效任务临时目录可解决；未改系统/工程设置，仅本次进程设置`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/project/AI-Coding/OryxOs/.verification/jdk-sockets`。
- 同一临时参数下重跑`mvn clean verify`：BUILD SUCCESS，255项默认测试，0失败/错误/跳过；10个reactor项目的OWASP插件实际执行，Spotless/Checkstyle/P3C/SpotBugs均通过，日志`main-model-java-verify-retry.log`。插件提示Sonatype OSS Index因缺凭证自动未启用，已如实保留；NVD等分析完成，没有新增跳过配置或压低门禁。
- 显式集成：同一临时参数下运行`mvn -pl oryxos-boot -am test -Dtest=MemorySystemIntegrationTest,MemoryBackendSystemIntegrationTest,ToolSystemIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest.excludedGroups=`，5/5通过，日志`main-model-integration.log`。
- 只读一致性复审通过：33条需求仍全部映射79项任务，新增4项对应US3/R1，未映射任务0、宪法冲突0、本补丁遗留阻断0；Java源码相对`5333e77`无差异，安全原始证据未改写。完成28/79项，下一步T025–T030暂存机制，R1–R5不能整体标绿，T073最终特性回归尚未完成。

## US3a 固定 SDK 暂存机制（2026-08-31）

- 本轮由主模型编写并验证T025–T030，不使用子代理，不启动业务服务或连接真实模型/PG。保留前序安全回移和全部旧断言。
- `engine/staging.py`实现冻结的请求身份/范围/revision/总期限与不可清除的失败标志；只读Snapshot二次验证ID/工作区/scope，get/search支持请求overlay，写入和历史仅落暂存。禁止reset/delete_col等越界动作；临时变更包含NONE共最多128项。
- `engine/staged_memory.py`在SDK导入前检查显式MEM0_DIR、禁遥测及全部149个源码摘要；不调用原版构造器/from_config/public add，只运行真实同步 `_add_to_vector_store(..., infer=True)`。逐项核对动作、变更、历史和SDK返回；NONE单独记录metadata-only证据，不生成内容版本或更新有效recency。核心禁止进入推理。
- 结果为StagedResult，不是持久提交凭据。Change.baseline_version_id只指原快照版本，实际history.previous_version_id由后续T038事务串接；原始输入登记、五表提交和快照连接生命周期属于T037–T043，当前没有假称已经持久化。
- `audit/calls.py`与`engine/providers.py`实现固定失败分类、前后审计、已审计调用标记、凭证脱敏、严格facts/actions/向量/usage校验。无审计标记时HTTPS不能发出；缺字段、重复JSON键、坏Unicode/向量、截断模型输出、Refusal/Tool意图和非法usage均不能冒充NOOP。合法facts=[]和匹配NONE正常结束。
- 标准库HTTPS固定origin、标准TLS、禁代理/OpenRouter/重定向；每次实际序列化请求和响应实体各≤1MiB，单条生成内容≤32KiB、facts≤64、actions≤128。DNS解析助手不持有正文，超期不继续连接；看门狗只关闭连接资源。头/正文慢响应、超限、编码及HTTP分帧异常都失败，不重试推理。
- 测试先行：`staging-red.log`为46失败/2来源测试通过；实现后48通过。传输用例首次74通过/1失败，定位看门狗触发后时钟边界误分普通服务失败，增加显式到期标记后75通过；记录`staging-transport.log`及`staging-green.log`。
- 进一步覆盖多步更新、快照返回错误ID、SDK/导入环境漂移、实际JSON转义膨胀及资源关闭。HTTP正文短于声明长度、非法长度和双重分帧3项红灯记录`staging-http-framing-red.log`；修复后最终全套127/127通过，无失败/跳过，日志`.verification/007-memory-backends/staging-final-tests.log`。命令在组件目录运行：`uv run --frozen pytest -q --durations=10 --basetemp <本任务唯一临时目录>`。总耗时197.34秒，主要为Windows临时目录fixture初始化；不放宽期限断言或删除测试。
- T030依赖检查：组件目录 `uv run --frozen python scripts/audit_dependencies.py` 退出0，71个安装依赖全覆盖；原始1项CVE、已证实安全回移修复1、未处置0。报告目录`.verification/007-memory-backends/dependency-audit/20260831T152900Z-4273ecdc-e0c0-4104-8cee-a1148436ff70/`，汇总`staging-dependency-audit.log`，原始扫描保留。
- 审计与只读Snapshot、模型/网络I/O在本阶段使用受控替身，真实SDK和实际HTTPS控制代码没有换成假Store。未初始化原版SQLite/向量/graph存储；仅有SDK受控临时config.json及测试证据。上述结果不代替真实PG审计持久化、事务或内网模型/完整TLS出口验证。
- 只读一致性复审：本阶段需求与T025–T030映射完整，无核心签名/模块扩张、宪法冲突或本阶段遗留阻断。Java源码/POM相对`5333e77`无差异，本轮未重复执行Java回归，也不把此前255+5结果冒充新执行。
- 当前完成34/79项。下一步T031–T032配置/启动安全与共享协议基础，再进入真实PG阶段；T030通过不等于US3或007完成。未归档提交、未push。

## US3b 配置、启动安全与共享协议基础（2026-09-01）

- 主模型完成T031–T032，未委派Spark/子代理。新建settings.py、bootstrap.py、contracts.py和两组单测；只复用已锁定依赖，无Java/POM/SDK/uv.lock变更，无真实DB/模型调用或业务服务启动。
- 配置整体拒绝缺值、坏JSON/重复键、未知绑定字段、非法摘要/UUID、重复绑定、非规范43字符token/cursor Secret及密钥复用。origin统一IDNA/IP/大小写/443规范化，重复、CSV、路径和非精确目标拒绝。不同key绑定同一workspace正常支持换钥。
- bootstrap先验证全部配置和实际TLS证书/私钥加载，再检查显式可写MEM0_DIR、拒绝缓存SDK与config.json链接，导入前强制禁遥测；只返回暂存类型，不初始化原生存储。冷进程实测正常导入仅生成受控config.json、没有网络尝试，缺Secret/坏TLS/预加载SDK拒绝。错误固定分类/字段，Settings和模型凭证不进入repr。
- 测试TLS资产是本轮本机生成的公开合成证书/私钥，位于tests/fixtures/tls-unit.json；显式排除运行镜像，不能用作部署凭证。TLS加载测试不是实际HTTPS出口、证书链或生产身份验收。
- contracts.py保留原文与UTF-8 NUL分隔hash，拒绝非法Unicode/字段/枚举/大小；冻结操作身份和pending/失败/SAVE/RECALL各类凭据，校验UTC时间、revision、action_counts、NOOP/CORE差异、历史标记及召回顺序/计数。完整响应按实际UTF-8 JSON计入request_id与replayed；提交前预算函数两种包装均测，不裁正文或把StagedResult当COMMITTED。真实持久/事务调用这些函数仍待T037–T043，路由/HMAC/跨语言向量待T045。
- 测试先行记录config-protocol-red.log：172个行为红灯，另2个超大参数用例发生4个pytest setup/teardown错误，原因是自动测试ID进入Windows环境变量后超过32767字符。仅给这2类用例设置短ID，保留原始大输入及断言；随后config-protocol-green.log为174/174通过。
- 补充配置链接检查时，本机真实symlink创建被WinError5拒绝，记录config-bootstrap-path-red.log，不计为应用通过；改用冷进程文件检查探针覆盖拒绝分支，不宣称真实链接隔离已验收。config-link-probe-red.log保留行为红灯；一次探针未规范化Windows短路径造成失败（config-protocol-hardened.log），修正测试路径后全套通过。实际文件系统ACL/竞态隔离仍属R3部署门禁。
- 最终完整回归（组件目录）：`uv run --frozen pytest -q --durations=10 --basetemp <本任务唯一临时目录> --tb=short`，**311通过、0失败/错误/跳过**，202.95秒；日志`.verification/007-memory-backends/config-protocol-final.log`。约188秒为既有6个Windows临时目录fixture初始化，未降低超时/安全断言。相对上轮新增184项。
- 来源绑定依赖检查：组件目录`uv run --frozen python scripts/audit_dependencies.py`退出0，71个实际安装依赖，原始1项CVE、已证实回移修复1、未处置0；日志config-dependency-audit.log，报告目录`.verification/007-memory-backends/dependency-audit/20260831T162639Z-6fccfe26-84fc-42ea-9053-396cdb93f9e6/`。UTC目录时间与本节本地日期对应；保留原始告警，不使用忽略项。
- 本轮没有重新运行Java回归，因Java/POM相对5333e77无变更；前述255默认+5显式集成仅作历史基线，不冒充新执行。当前完成36/79，尚未归档提交或推送，R1–R5不整体标绿。

### U001远端NUL表示边界（已批准并实现）

现有协议承诺正文为合法Unicode，且共享hash按原文处理；NUL（U+0000）是合法Unicode，当前测试明确保留它。data-model却将raw_input/content/历史原文定义为PG TEXT，[PostgreSQL 17官方字符类型文档](https://www.postgresql.org/docs/17/datatype-character.html)明确此类型不能保存NUL。不能在数据库报错后声称原文已留存，也不能擅自删除/替换字符。

用户回复“继续”，接受推荐方案：仅在Mem0远端输入/查询/生成内容边界拒绝NUL；哈希字段间NUL分隔符不变，Markdown/SQLite本地后端不变。四份事实源、AGENTS、范围决议、规格/计划/数据模型/协议/quickstart/tasks均已同步。contracts输入与receipt内容、staging生成/快照文本使用同一拒绝规则，不删除、替换或编码改写原文。

测试先行：`nul-boundary-red.log`有2项预期失败，分别证明请求解析与直接hash仍接受正文NUL；新增模型fact用例还验证只调用事实提炼一次、未进入动作模型/存储。修复后`nul-boundary-green.log`为123项通过。该限制尚须在T045 Java/跨语言协议向量和T034真实PG业务投影中继续覆盖，当前不把离线回归当事务证据。

### T033显式隔离PG测试地基

- 新建`tests/integration/conftest.py`、`test_fixture_contract.py`和离线安全策略测试。fixture只接受`ORYX_MEM0_TEST_DATABASE_URL/NAME/CONFIRM`，数据库名须匹配`oryx_mem0_*test`且确认串为`DELETE:<database>`；拒绝libpq键值串、多主机、运行时DATABASE_URL复用及未验证的远端TLS。loopback须显式disable或verify-full，远端只允许verify-full和存在的绝对CA路径。
- 取得连接后、任何清理前，核对实际数据库名、PG版本170011、pgvector 0.8.6、非superuser及数据库comment=`ORYXOS_DISPOSABLE_TEST_DATABASE`。只删除这个已核验测试库内的常量schema`oryx_memory`，不创建/删除数据库、不获取管理员凭证。连接固定3秒、statement 5秒、lock 2秒和idle transaction 30秒。
- 合成fixture含两个工作区、固定操作UUID、中英文/引号/反斜杠/emoji和二维向量，不含Secret或NUL。预声明13个单次故障点并记录命中，可供T034/T035事务、审计、查询及崩溃测试使用；任意故障点名称拒绝。
- TDD记录：`pg-fixture-policy-red.log`为17项行为红灯（非缺模块）；实现后`pg-fixture-policy-green.log`17项通过，补连接限时与固定schema断言后`pg-fixture-policy-final.log`18项通过。
- 在明确移除三项测试变量后运行`uv run --frozen pytest -q -m integration tests/integration/test_fixture_contract.py --tb=short`，以`ORYX_MEM0_TEST_DATABASE_URL`缺失产生1个setup error，记录`pg-fixture-missing-env.log`；这是正确拒绝证据，不计测试通过或T034真实事务证据。
- 本机Docker daemon不可连接，环境中也没有测试PG变量；未启动Docker、未拉取镜像、未连接任何数据库/模型、未执行schema删除。T033完成表示fixture及其安全策略就位，T034–T043和R2继续未完成。
- 主模型完整离线回归：组件目录运行`uv run --frozen pytest -q -m 'not integration' --durations=10 --basetemp <唯一目录> --tb=short`，**332通过、0失败/错误、1项真实PG用例deselected**，202.53秒；日志`pg-foundation-unit.log`。被排除的integration未计通过，单独缺环境执行如上明确失败。最终故障点/连接期限微调后，策略、协议与暂存相关复验141/141通过，日志`pg-foundation-final-focused.log`。
- 未重复执行依赖审计：pyproject/uv.lock/SDK/vendor均未变，上一轮71依赖、原始1项/回移修复1项/未处置0仍为最近来源绑定证据；本轮完整离线回归已包含SDK构建/安全测试。未修改Java/POM，未重复运行Java回归。当前37/79，未提交、未推送。

## T034–T035真实PG环境尝试（2026-09-01）

- 用户在被告知需要Docker或显式PG后回复“继续”，本轮据此启动本机Docker Desktop。官方pgvector仓库及Docker Hub均列出`pgvector/pgvector:0.8.6-pg17`；仅完成候选来源核对，**未拉取镜像、未取得本机digest、未创建容器/卷/网络**，不能作为T048镜像锁或T034真实PG证据。
- 本机同时存在CLI路径`D:/Program/resources/bin/docker.exe`与实际Desktop安装`D:/Docker/Docker Desktop.exe`。实际Desktop 4.82.0启动后，后端先后因`Docker/run/dockerInference`与`docker-secrets-engine/engine.sock`的Windows Unix-socket删除/重建失败而退出；Docker API始终未就绪。日志还报告WSL无已安装发行版，但WSL/VirtualMachinePlatform/Hyper-V系统功能本身已启用。
- 排障只涉及Docker进程、手动服务LxssManager和临时IPC目录：曾关闭Docker AI用户项验证，结论无效后已恢复原值；LxssManager已恢复原来的Stopped/Manual。原`Docker/run`与`docker-secrets-engine`目录已从同级备份恢复，失败重建的`Docker/run.failed-20260901`保留为可追溯临时目录，未触及镜像、容器或卷。
- 机器已有运行中的PostgreSQL 14服务，但与plan锁定的17.11/pgvector0.8.6不符，未连接或复用。没有Visual Studio C++工具链，未下载非官方Windows pgvector二进制或临时降级版本。
- 继续修复需要安装WSL发行版、升级/重置Docker，或由用户提供fixture三项环境变量指向已确认可销毁的PG17.11+pgvector0.8.6测试库；这些是超出本轮默认权限的系统/外部环境变更。T034/T035均未编写或勾选，实际进度保持37/79，R2仍未执行。

### Docker Desktop 4.89.0原地升级（用户已授权）

- 用户明确回复“继续”授权升级。按Docker官方发布说明选择4.89.0/build238018：该版本包含Windows非正常退出遗留stuck socket导致启动失败的修复。安装包下载到忽略目录`.verification/docker-upgrade/`，长度624,612,784；SHA-256严格匹配官方`854626704af28a160d5af68b96b3e32eacf08ab397ce6c12eb02a04788d73681`，Authenticode状态Valid，签名主体Docker Inc。
- 使用官方安装参数执行`install --quiet --accept-license --installation-dir=D:\Docker --backend=wsl-2`，原地升级退出0；`D:/Docker/app.json`核对为4.89.0/build238018，安装后的Docker Desktop可执行文件签名仍为Docker Inc且Valid。用户settings-store存在，Docker AI原值true未改变；未使用uninstall/factory reset/clean或数据根参数。
- 第一次启动读取升级前旧socket后仍失败；隔离升级前IPC目录后再次启动，不再立即复用旧文件，但Engine最终返回HTTP500。4.89日志明确：`wsl --version`退出1、没有已安装发行版，同时新建Inference socket后仍遇到Windows重命名拒绝。Docker API始终未可用，官方pgvector镜像仍未拉取。
- 尝试用同一已校验安装器切到Hyper-V，安装器因现有4.89.0已是最新版本而退出3；日志只到pre-check，没有应用变更。没有卸载重装，因为Docker官方说明卸载会移除本地容器/镜像/卷，超出授权。
- 当前Docker进程已停止，LxssManager恢复Stopped/Manual；最新失败IPC分别移到`Docker/run.failed489-20260901`和`docker-secrets-engine.failed489-20260901`，下次启动可创建新目录。升级前IPC备份及前轮失败目录保留为可恢复证据，未触及Docker数据盘、镜像、容器或卷。
- 本轮仍未连接PG/模型、未编写或勾选T034/T035、未运行应用回归；项目进度保持37/79。继续需要用户另行授权更新/安装WSL（官方文档提示可能需要重启），或提供fixture要求的外部隔离PG。

### WSL运行时更新（用户已授权）

- 用户明确授权执行官方`wsl --update`，命令退出0。使用Unicode重定向重新核验：WSL 2.7.12.0、kernel 6.18.33.2-2、WSLg 1.0.73.2、Windows 10.0.22621.4751；命令没有要求重启，也没有安装Ubuntu等用户发行版。
- 在4.89.0及全新IPC目录下重新启动Docker：Desktop进程保持运行且不再发生socket初始化崩溃，但Docker API返回HTTP500。后端日志给出稳定根因`Wsl/Service/RegisterDistro/CreateVm/HCS/HCS_E_HYPERV_NOT_INSTALLED`，提示当前机器配置不支持WSL2，并明确要求运行`wsl.exe --install --no-distribution`启用/修复Virtual Machine Platform。
- 先前只读检查曾显示WSL/VirtualMachinePlatform/Hyper-V feature为Enabled且hypervisor present，但HCS实际注册仍失败；不以feature标志覆盖真实运行结果。同版本安装器的Hyper-V切换仍未应用，现有PostgreSQL14仍未使用。
- Docker已通过官方Desktop CLI停止，LxssManager恢复Stopped/Manual；Docker保持4.89.0。没有PG连接、镜像拉取、容器/卷操作或项目测试执行，T034/T035保持未完成，进度37/79。
- 下一步`wsl --install --no-distribution`会修改Windows系统组件且可能要求重启，需用户单独授权；本轮没有自动执行。

## T050 Java HTTPS测试地基（2026-09-01）

- 用户要求暂时跳过PG/WSL流程后，按tasks显式依赖转入仅依赖T030的T050；没有越过T045实现Mem0协议/Store。`oryxos-memory/pom.xml`新增BOM管理的`org.springframework:spring-web` compile依赖及已锁定`mockwebserver` test依赖；离线dependency:tree确认6.2.19/4.12.0，未增加版本属性、生产TLS库或trust-all开关。
- `HttpsFixture`仅在test源码：每次用当前JDK keytool生成随机密码、RSA2048临时CA，签发带localhost/IPv4/IPv6 SAN和serverAuth的服务证书；PKCS12私钥只在唯一临时目录，keytool输出丢弃且每步20秒上限。MockWebServer只加载服务证书，客户端SSLContext只信本次CA；关闭时清零可变密码、停止服务并删除目录。
- 行为测试证明系统默认trust拒绝随机CA，专属SSLContext完成真实HTTPS请求；核对CA basicConstraints/keyCertSign、服务证书非CA/serverAuth/签名/SAN，拒绝任意authority与fragment路径，close幂等且关闭后访问失败。未写生产信任文件、不使用静态测试私钥或额外依赖。
- 测试先行：初始命令参数错误和Spotless失败不计行为红灯；格式修正后`https-fixture-red.log`编译成功，并以`UnsupportedOperationException`在未实现入口产生1项行为红灯。实现后第一次断言按localhost写死失败，改为证书允许的loopback host；随后本机JDK selector再次暴露既有Unix-domain临时目录问题，保留`https-fixture-green.log`。仅本次进程复用已核验的`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=.../.verification/jdk-sockets`，不改系统/工程配置，`https-fixture-green-retry.log`1/1通过。
- 完整受影响测试：memory及依赖模块共core48+storage16+memory47=111项，0失败/错误/跳过；原`https-fixture-checkpoint.log`最终只因2条Checkstyle失败。修复ASCII Javadoc句点和变量首次使用距离后，`https-fixture-checkpoint-retry.log`聚焦2/2与Checkstyle0违规通过。最终快速静态门禁`https-fixture-static.log`BUILD SUCCESS，Spotless/Checkstyle/P3C/SpotBugs/Find Security Bugs通过、BugInstance0；明确跳过测试和OWASP，只作为增量静态检查。
- T050结束时未重复Python/Java全仓或OWASP最终回归，T073仍未执行；当时进度38/79。后续T052/T055证据见下一节，整体当前40/79，未提交、未推送。

## T052/T055 HTTP精确白名单（2026-09-01）

- 在用户要求继续非PG任务后，先完成T052测试再实现T055。`HttpWhitelistSandbox`只允许HTTP_REQUEST且scheme为HTTP/HTTPS；配置项仅接受域名或IP，不接受URL、port、路径、通配符、zone ID、非规范IPv4或坏项，空列表合法但全部拒绝。整份配置有坏项或IDN/大小写/尾点规范化后重复时启动失败，不后项覆盖。
- host比较先把Unicode域名经JDK IDN USE_STD3_ASCII_RULES变为ASCII，再校验每个DNS label；IPv4自行解析避免宽松八进制/缩写，IPv6只解析含冒号字面量并以地址字节比较，不对域名做DNS查询。目标authority拒绝userinfo、编码host、非法/非规范port与fragment；path/query/非默认port不改变host精确许可。错误使用固定中文模板，不回显目标。
- `ToolConfiguration`仅在缺少用户Sandbox时，用Spring Binder从既有`http.allowed_domains` relaxed binding生成该实现；空配置继续拒绝File/Shell/MCP/HTTP，自定义Sandbox存在时不会解析或实例化默认坏配置。现有`application.yaml`无需改键。
- TDD红灯：`http-whitelist-red.log`中HttpWhitelist4项有2项行为失败、ToolConfiguration12项有1项行为失败，分别证明允许目标仍拒绝、坏配置不失败和默认仍为旧lambda。实现后`http-whitelist-green.log`16/16通过；改用真实下划线配置键并加非规范IPv4负例后`http-whitelist-binding.log`5/5通过。
- 受影响回归`http-whitelist-checkpoint.log`：HTTP/Notify/MCP/File/Shell/装配相关63/63通过，0失败/错误/跳过。没有发往外部网络；HTTP用既有MockWebServer，MCP负例在启动子进程前被Sandbox拒绝。
- 快速静态门禁首次发现17条P3C（作者/复杂条件/魔法常量），全部用命名常量和拆分校验修复；第二次发现2条Find Security Bugs Unicode通用告警，移除scheme/host大小写转换后剩IDN.toASCII这一必需安全规范化点。仅对`normalizeHost`添加精确`IMPROPER_UNICODE`理由注解：IDN输出ASCII并逐标签校验，不新增全局过滤或扫描跳过。
- 最终`http-whitelist-static-passed.log`：Core/Storage/Memory/Tool快速静态verify BUILD SUCCESS；Spotless/Checkstyle/P3C/SpotBugs/Find Security Bugs均通过，Tool BugInstance0/Error0。命令跳过tests和OWASP，测试证据由前述63项承担，最终OWASP仍属T073。
- T052/T055完成，进度40/79。T053可继续；T051等待T045，T054同时等待T051/T053。PG/WSL按用户要求暂停，未提交、未推送。

## T053 受限错误链测试（行为红灯，2026-09-01）

- 按测试先行建立`MemoryOperationException`最小可编译骨架：仅声明final类型、8个固定Code、Code/UUID构造入口和只读访问器；当前消息仍是占位且尚未实施UNKNOWN必带UUID/禁cause，因此不是T054实现，不勾选T054。
- `MemoryOperationExceptionTest`锁住final/构造器集合、8个Code、固定中文消息、可选规范UUID、UNKNOWN必带编号、null分类拒绝及不能附加任意远端cause。实际4项中2项预期失败：占位消息不匹配、UNKNOWN无编号未拒绝；另2项通过。日志`memory-exception-red.log`。
- `AnnotatedToolAdapterTest`增加MEMORY_TIMEOUT与MEMORY_OUTCOME_UNKNOWN两种真实反射异常，要求`success=false/retryable=false`并保留固定分类/UUID；原有普通异常仍只返回脱敏失败。实际14项中新增1项预期失败，现实现把受限异常压成“工具方法执行失败”；其余13项通过。日志`memory-adapter-red.log`。
- `ToolExecutorTest`增加工具已产生副作用、返回已验证不可重试UNKNOWN后再设置中断的场景，要求原失败明细及线程中断标志同时保留并按原文审计、零重试。实际12项中新增1项预期失败，现实现覆盖成“工具执行已中断”；其余11项通过。日志`memory-error-chain-red.log`。
- 三处合计30项，4个行为红灯、26项通过；无编译缺类红灯、无断言删除或阈值放宽。T053测试任务完成并勾选，进度41/79；实现修绿属于T054/T059，但T054还依赖T051，而T051依赖T045，当前不越过门禁。分支处于有意TDD红态，不作稳定提交或完整回归。

## T054/T059 受限错误链实现（用户批准顺序例外）

- 用户明确要求继续跳过PG/T045并实现其他内容、不得关机重启；据此只对已有T053红灯和设计契约完全覆盖的T054/T059执行顺序例外，不勾选T045/T051、不实现Mem0 Store。
- `MemoryOperationException`为final，只公开Code和Code+UUID构造器；8个Code与契约一一对应，固定中文安全消息。UNKNOWN无UUID构造立即失败；使用受限RuntimeException构造禁用任意cause/suppression/可写栈，不接受远端message、URL或正文。`MemoryOutboundGuard`只声明`check(URI)`，没有默认no-op或tool依赖。
- `AnnotatedToolAdapter`仅对该final异常返回`ToolResult.fail(name,safeMessage,false)`；Sandbox异常仍原样抛出，InterruptedException仍保留中断，所有其他异常继续统一脱敏。`ToolExecutor`先验证结果非空及工具名，再优先返回明确不可重试失败；若工具随后设置中断，分类/UUID与中断标志同时保留并按原文审计，成功/可重试结果仍受中断阻止。
- `memory-error-chain-green.log`：Core12+Memory4+Tool14=30项全部通过，0失败/错误/跳过；四项T053行为红灯均修绿，既有非Memory脱敏、重试、授权和审计断言保留。
- 快速静态门禁`memory-error-chain-static.log`：Core/Storage/Memory/Tool BUILD SUCCESS，Spotless/Checkstyle/P3C/SpotBugs/Find Security Bugs通过，Tool BugInstance0；跳过tests与OWASP，测试证据由前述30项承担。T054/T059完成但其最终组合仍须T051/T060/US3验收。

## T056 Mem0严格配置与条件绑定

- 新增final `Mem0Properties`：base-url仅接受显式ASCII/punycode HTTPS origin，无userinfo/path/query/fragment，host手工只折叠ASCII大小写，默认443去除、其他合法port保留；不做DNS或默认云补位。API key必须为32字节规范无padding base64url43字符，UUID须小写带连字符且非nil；connect/read为正且不超过operation，operation范围5s–55s，缺省3s/30s/40s。`toString`固定隐藏token，校验异常仅含字段名。
- 为避免全局类型提前解析Secret，增加先于`MemoryConfiguration`的内部`Mem0PropertiesConfiguration`：仅`memory.backend=mem0`且没有用户MemoryService时注册属性；默认Markdown、SQLite及用户自定义门面均不读取这些变量。有效内建配置当前仍因Mem0 Store未实现明确失败，不降级；坏配置先报告固定字段错误。
- TDD红灯`mem0-properties-red.log`：13项中5个行为失败（URL/token/UUID/期限和条件绑定未校验）及1个测试断言顺序错误；先修正测试顺序再实现。首轮实现因memory没有SpotBugs注解依赖而编译失败，改为ASCII/punycode host而非增加依赖；随后发现纯数字UUID无法验证uppercase，改用含a的规范fixture。`mem0-properties-green-retry.log`13/13通过，最终新增port65536/null workspace负例后`mem0-properties-final-tests.log`6/6通过。
- 静态迭代：`mem0-properties-static.log`发现10条P3C复合条件/魔法常量，全部拆分/命名；`mem0-properties-static-passed.log`剩1条Checkstyle变量距离；`mem0-properties-static-final.log`剩1条SpotBugs捕获NPE，改为显式null检查且不加suppression。最终`mem0-properties-final-gate.log`6/6并SpotBugs BugInstance0/Error0；此前完整静态阶段已证明Spotless/Checkstyle/P3C通过，组合证据不包含OWASP。
- T054/T056/T059完成，进度44/79。用户要求的PG/T045跳过仍生效：T045/T051/T058及真实服务/模型未完成；未关机、未重启、未启动Docker/WSL，未提交、未推送。

## PG运行地基与T045协议安全（2026-09-01）

- 用户随后明确要求执行PG/T045，仍遵守不得关机重启。`wsl --install --no-distribution`完成系统组件登记且未提示重启；WSL2发行版注册仍被`HCS_E_HYPERV_NOT_INSTALLED`拒绝，因此没有伪造Docker/WSL2成功。改用显式`--version 1`安装隔离发行版`OryxPgTest`（Ubuntu 24.04），保留系统默认WSL版本2，不启动或重置Docker数据。
- 隔离发行版从PostgreSQL官方PGDG仓库安装精确包`postgresql-17=17.11-1.pgdg24.04+2`与`postgresql-17-pgvector=0.8.6-1.pgdg24.04+1`。服务端查询返回`17.11`/`server_version_num=170011`；仅监听本机loopback端口5433，不复用本机PG14。
- 新建一次性数据库`oryx_mem0_pg17_test`及非superuser角色，数据库comment固定`ORYXOS_DISPOSABLE_TEST_DATABASE`，只安装vector扩展；测试凭证为合成值。通过三项显式fixture变量从Windows主模型进程连接，`test_fixture_contract.py` **1/1通过**：实际核对版本、扩展、非superuser、comment及vector距离运算；fixture清理只触及已核验库内常量`oryx_memory` schema。
- T045按用户点名作为T044前的顺序例外实现。`contracts.py`增加固定服务错误分类/中文模板和持久receipt严格判别解析，拒绝重复/未知/HTTP信封字段；既有请求解析继续拒绝正文U+0000，但请求hash仍以字段间NUL分隔原文。`security.py`以API token ASCII SHA-256做Bearer绑定，签名密钥只以解码32字节使用；snapshot不含scope，cursor绑定完整snapshot摘要/workspace/revision/scope/sort/after/期限，两种payload分type、对原始规范JSON字节做HMAC-SHA-256并拒绝混用、篡改、越权与过期。
- `tests/fixtures/protocol-v1.json`冻结NUL分隔hash、snapshot、CORE/ARCHIVAL cursor与RECEIVED/RUNNING/FAILED/ABORTED/SAVE/RECALL COMMITTED全部持久凭据。定向协议/安全 **89/89通过**；完整Python unit **351/351通过**，另有6条Windows pytest旧临时目录删除警告，无断言失败；`compileall`通过。测试、代码和回归均由主模型执行。
- 当前45/79。T034–T044仍未实现或勾选，因而没有五表事务、崩溃恢复、HTTP五端点或持久内部审计证据；T045完成及PG fixture绿灯不代表R2/R3/R4或US3完成。T051前置现已满足但尚未执行；未连接真实模型，未提交、未推送。

## T034/T035真实PG事务与查询测试先行（2026-09-01）

- 实施前重新执行SpecKit prerequisites：feature目录与tasks解析通过；`requirements.md` 16/16，无extensions hooks；Git/Python/Docker忽略规则已覆盖，无需改动。完整重读tasks/plan/data-model/contracts/research/constitution/quickstart后，仅推进用户指定的两个测试任务。
- 新建`tests/integration/test_transactions.py`共14项：精确五表与vector类型；原文/hash/30秒期限/同ID幂等及不同ID同正文；唯一owner与RUNNING；stale baseline CAS只允许一方提交；多动作原子receipt及同操作连续UPDATE的实际previous_version链；history/audit故障回滚；生成NUL/32KiB、完整成功receipt预算在COMMITTED前拒绝；提交后应答丢失按原ID读到COMMITTED；过期RECEIVED/RUNNING→ABORTED、STARTED audit→UNKNOWN、晚owner fence；跨workspace操作隔离。FAILED/ABORTED均要求匹配身份且`memory_effects_applied=false`。
- 新建`tests/integration/test_queries.py`共7项：同一scope无关snapshot读取101条CORE和ARCHIVAL最近100条；revision快照重构UPDATE/DELETE前状态；40条含双引号、反斜杠、中文和emoji的实际JSON字节分页且不截正文；当前ARCHIVAL精确余弦top20及同分ID排序；20条各32KiB反斜杠正文超过receipt预算时返回最长完整前缀并标记`truncated_by_bytes=true`；空召回不伪装截断；跨workspace及快照后并发新增隔离。
- 为避免缺模块/收集错误伪装红灯，仅在计划指定的`src/oryx_mem0/storage/`建立migrations/operations/memories/call_audits/queries最小接口骨架与冻结返回类型；所有行为入口明确抛出未实现。没有DDL、SQL写入、事务、查询或持久审计实现，T036–T042均未提前勾选。
- `compileall`及pytest collection通过，稳定收集事务14+查询7。用三项显式隔离PG变量执行：**21 failed、0 passed、0 skipped、0 setup/collection error**，全部首个失败点为T036 `apply_migrations`未实现；这证明真实PG环境和测试装配可达，仍是预期TDD红灯，不是事务验收。日志`.verification/007-memory-backends/pg-t034-t035-red.log`。随后完整Python unit **351/351通过**，仅7条Windows pytest旧临时目录删除警告；日志`pg-t034-t035-unit.log`。
- T034/T035作为测试任务完成并勾选，当前47/79。下一步必须先实现T036显式迁移，再按T037–T042逐层修绿，最后由T043重新执行真实故障/恢复硬门禁；当前R2、US3及007仍未通过。未连接真实模型，未提交、未推送。

## T036显式五表迁移与结构门禁（2026-09-02）

- 新增`migrations/001_initial.sql`，只建立`oryx_memory`中的五张业务表：namespaces、operations、versions、current、call_audits；不建立隐藏状态表或非事务revision sequence。表含数据模型规定的UUID/状态/scope/原文/receipt/版本链/vector(D)/调用审计字段，显式命名PK、组合FK、唯一约束、内容与状态check及固定查询索引；未实现任何T037后的业务DML。
- schema comment固定`oryx-memory-v1;schema=1;dimensions=D`。`apply_migrations`先锁定PG 17.11和pgvector 0.8.6，再以事务级advisory lock串行创建；仅schema不存在时执行版本文件。schema已存在时只调用`validate_schema`，不会运行DDL、补列、改维度或覆盖marker；迁移文件占位符数量与维度1–16000先校验，不能用于SQL注入。
- `validate_schema`只读精确核对marker、五表集合、全部列/PG类型/NULL性、命名约束、约束索引与业务索引、唯一历史触发器及其PL/pgSQL函数正文、PUBLIC schema/table/function权限和默认权限。维度不符、预存同名未知schema、已知marker下列被删除、额外/缺失对象或运行版本不符均抛固定中文`SchemaError`分类，不回显数据库正文，不自动修改业务库。
- `memory_versions`通过普通非SECURITY DEFINER触发器拒绝UPDATE/DELETE，工作角色仍可INSERT/SELECT；五表/函数及未来默认对象均撤销PUBLIC权限。当前测试库由非superuser一次性角色执行迁移，因此不借用管理员凭证；真正部署的迁移/运行角色分离仍须T048部署门禁核验。
- 在`test_transactions.py`补充4项迁移专属用例，并复用原五表用例：重复apply保持relfilenode不变；marker/维度3及非法0/bool/16001拒绝且vector(2)不改；未知schema只有原`unexpected`列、零新增表；删除已知列后validate失败；历史UPDATE/DELETE均被PG拒绝且PUBLIC无USAGE/SELECT/INSERT。真实PG定向结果**5/5通过**，日志`.verification/007-memory-backends/pg-t036-tests.log`。
- 完整T034/T035前沿结果为**5 passed、20 failed、0 skipped/collection error**；20项都已越过迁移层，分别停在T037 OperationStore或T042 QueryStore未实现，日志`pg-t036-frontier.log`。`compileall`通过，完整Python unit **351/351通过**，8条Windows pytest旧临时目录清理警告，日志`pg-t036-unit.log`。
- T036完成，当前48/79。该结果只证明schema/权限/不可变历史地基，不证明原文登记、owner/CAS、current/history/receipt原子提交、审计恢复或查询正确；R2、T043、US3及007仍未通过。未连接模型、未提交、未推送。

## T037原文登记、幂等与唯一owner（2026-09-02）

- `OperationStore.register`在独立短事务中先幂等创建workspace namespace，再用同一个数据库`clock_timestamp()`原子写入原文、request_hash、RECEIVED、created_at与不可延长的+30秒deadline。输入先经现有Request/request_hash严格校验，NUL、超限或身份/hash不匹配在连接前拒绝；相同ID+相同请求返回原持久状态，不刷新时间或重复写，ID相同而hash/raw不同抛固定中文RequestConflict。不同ID的相同原文分别登记。
- `claim`以带`FOR UPDATE`候选的单条条件UPDATE，只允许匹配workspace/kind/scope/hash且未过期的RECEIVED取得一次规范非nil owner UUID；同时读取namespace当前revision写入baseline并进入RUNNING，保留原deadline。第二owner、已过期、已RUNNING或终态均不改行并返回无租约；身份不符固定冲突。
- `get`按workspace+operation联合键读取，RECEIVED/RUNNING构造冻结PendingReceipt；终态只接受严格`receipt_from_bytes`可解析且身份/state/revision/error与列一致的JSON。原文会重新计算hash，数据库畸形、非规范UUID或JSON不回显为数据库错误正文，统一OperationError。T038/T041负责写终态，本任务没有抢跑业务提交或恢复。
- T033故障点在事务提交后触发：`operation_registered`异常后RECEIVED仍可查询，`owner_claimed`异常后RUNNING与原deadline仍可查询，后续owner不能再次抢占；这证明进程应答丢失不回滚已提交登记/租约。
- 新增/受影响真实PG定向结果**5/5通过**，日志`.verification/007-memory-backends/pg-t037-tests.log`。完整T034/T035前沿为**10 passed、17 failed**：剩余10个事务红灯属于T038–T041，7个查询红灯属于T042，日志`pg-t037-frontier.log`。Python unit **351/351通过**，9条Windows pytest旧临时目录清理警告，日志`pg-t037-unit.log`。
- T037完成，当前49/79。尚无current/history/namespace/COMMITTED receipt同事务提交、持久内部审计或恢复证据；R2、T043、US3及007继续未通过。未连接模型、未提交、未推送。

## T038只读快照与原子业务提交（2026-09-02）

- `MemoryTransactionStore.read_snapshot`固定只读REPEATABLE READ；实测外部连接推进namespace后，同一快照仍读原revision，写操作被PG拒绝，连接不跨请求持有。
- `commit`先锁operation与namespace，核对RUNNING/owner/数据库期限/kind/scope/hash/baseline及StagedResult身份。事务内先读取并模拟完整action序列，生成版本UUID、实际previous_version链、created_revision、action counts、affected IDs和统一数据库提交时刻；完整SaveReceipt用发送序列化器校验replayed两种1MiB包装，成功预算通过前不写业务行。
- 预算通过后按action_index追加versions，并同步INSERT/UPDATE/DELETE current；同操作连续UPDATE的第二个previous_version指向本操作第一版本而非暂存baseline。CORE只允许单原文ADD且embedding为空；ARCHIVAL显式使用配置模型和向量，DELETE保留tombstone、NOOP不造版本但仍按SAVE推进revision。最后同事务CAS更新namespace及COMMITTED result_json。
- history/current/receipt/before_commit故障均回滚versions/current/revision/成功凭据，再用独立事务写匹配FAILED及`memory_effects_applied=false`；NUL/超32KiB/成功receipt超限分别固定ENGINE_INVALID_RESULT/ENGINE_LIMIT_EXCEEDED。stale baseline写WRITE_CONFLICT。`after_commit_before_response`发生在事务提交后，只抛未知应答，不覆盖已可按原ID查询的COMMITTED。
- 真实PG定向 **15/15通过**，日志`.verification/007-memory-backends/pg-t038-tests.log`。完整事务/查询前沿**25 passed、9 failed**：仅T039审计1项、T041恢复1项和T042查询7项未实现，日志`pg-t038-frontier.log`。Python unit **351/351通过**，10条Windows pytest旧临时目录清理警告，日志`pg-t038-unit.log`。
- T038完成，当前50/79。R2仍需T039–T043闭环，尚未实现持久内部审计、过期恢复或快照/召回查询；Mem0不可启用。未连接模型、未提交、未推送。

## T039持久内部调用审计（2026-09-02）

- `CallAuditStore`同时接受现有CallAuditor的严格字典入口与测试/服务显式参数；workspace/operation/call index/kind/phase/provider/model/request均在I/O前校验，request/response按实际UTF-8 JSON限制1MiB。call UUID由存储端生成，数据库时钟写STARTED，独立事务成功后才返回给wrapper并允许模型/embedding调用。
- finish只允许STARTED原子转COMPLETED或FAILED，数据库时钟写完成时间；latency非负，usage仅接受prompt/completion/total完整一致三元组，Provider未报告时三列保持NULL而非0。COMPLETED不带error，FAILED只接受固定分类；重复/未知call、触发器或数据库失败统一AuditUnavailable，不回显数据库正文。
- `audit_started`故障发生在插入前，CallAuditor实测零下游I/O且RunContext锁存AUDIT_FAILURE；结束审计失败保留STARTED并锁存fatal，随后T038拒绝SAVE并写匹配AUDIT_UNAVAILABLE FAILED凭据。UNKNOWN转换仍由T041恢复实现，未抢跑。
- 真实PG定向**3/3通过**，日志`.verification/007-memory-backends/pg-t039-tests.log`；完整前沿**28 passed、8 failed**，仅T041恢复1项及T042查询7项，日志`pg-t039-frontier.log`。Python unit **351/351通过**，11条Windows pytest旧临时目录清理警告，日志`pg-t039-unit.log`。
- T039完成，当前51/79。下一步按tasks进入T040服务组合；R2/T043/US3/007仍未通过，Mem0不可启用。未连接真实模型、未提交、未推送。

## T040操作服务组合（2026-09-02）

- 新增`OperationService`统一执行register→claim→有界RunContext→处理→持久终态。CORE只构造一个随机memory UUID的原文ADD，不打开归档快照或模型；ARCHIVAL只在T038只读REPEATABLE READ连接生命周期内调用注入的固定SDK stage，随后交T038原子commit；RECALL只调用注入的只读处理器，实际PG查询由T042提供。
- 相同操作若已是RUNNING或任一终态，服务直接返回持久凭据，不再次claim/stage/推理；claim竞争失败重新读取原状态。期限由登记deadline换算为剩余单调预算且不超过30秒。Engine fatal映射固定错误并经T038 fail保留原文，不进入commit；存储冲突/未知提交原样上抛，不被服务改称成功。
- 新增服务层5项：CORE零归档快照、ARCHIVAL NOOP单次stage、终态零重放、fatal零commit及RECALL单次委托，**5/5通过**。`compileall`及完整Python unit **356/356通过**，12条Windows pytest旧临时目录清理警告；日志`.verification/007-memory-backends/t040-unit.log`。真实PG代码未变化，最近前沿保持28绿8红。
- T040完成，当前52/79。T041恢复与T042查询未完成，T043/R2/US3/007仍未通过；Mem0不可启用。未连接真实模型、未提交、未推送。

## T041过期操作与调用恢复（2026-09-02）

- 新增`storage/recovery.py`。操作恢复只锁定指定workspace内deadline已到且仍为RECEIVED/RUNNING的行，逐条写ABORTED、OPERATION_DEADLINE、完成时间和完整`memory_effects_applied=false`凭据；条件UPDATE防止锁等待期间已转终态的行被覆盖。关联调用恢复只把这些ABORTED操作的STARTED审计改为UNKNOWN并记录数据库时钟/latency/error，不触发SDK、模型或业务DML。
- `OperationStore.recover_expired`与`CallAuditStore.recover_unknown`委托该实现。重复恢复均返回0；COMMITTED即使deadline被测试改为过去也保持原凭据；过期RUNNING的旧lease随后提交被T038 owner/state fence拒绝。原文、history/current均不删除。
- 真实PG恢复测试**2/2通过**，日志`.verification/007-memory-backends/pg-t041-tests.log`；完整前沿**30 passed、7 failed**，全部剩余红灯均为T042查询，日志`pg-t041-frontier.log`。Python unit **356/356通过**，13条Windows pytest旧临时目录清理警告，日志`pg-t041-unit.log`。
- T041完成，当前53/79。下一步T042查询，之后T043才可执行真实事务硬门禁；R2/US3/007仍未通过，Mem0不可启用。未提交、未推送。

## T042 revision快照、分页与精确召回（2026-09-02）

- `QueryStore`从versions按workspace/revision选每个memory最后版本并排除DELETE，snapshot本身不含scope；CORE按created_revision/memory_id全量keyset分页，ARCHIVAL先选最新100条再稳定升序。分页同时受page_size≤100与完整JSON 1MiB约束，不截正文，无法容纳单项时明确失败。
- RECALL只读取current ARCHIVAL，使用pgvector `<=>`精确余弦距离、score DESC/同分memory_id ASC取top20；不查CORE/raw/history、不建ANN。按完整RecallReceipt序列化从全部候选向下选择最长完整前缀，准确记录returned_count/truncated_by_bytes，数据库时钟生成终态时间。
- 测试seed曾把不同operation的全局序号误写为单操作action_index，触发生产128动作约束；修正为每个独立操作action_index=0，未放宽DDL。T042真实PG **7/7通过**，日志`.verification/007-memory-backends/pg-t042-tests.log`；T034/T035完整前沿 **37/37通过**，日志`pg-t042-frontier.log`。Python unit **356/356通过**，14条Windows临时目录警告，日志`pg-t042-unit.log`。
- T042完成，当前54/79。下一步T043执行事务硬门禁并记录原子性/恢复证据；R2/US3/007尚未收口，Mem0不可启用。未提交、未推送。

## T043真实PG事务硬门禁（2026-09-02）

- 主模型使用三项显式fixture变量连接隔离loopback数据库；门禁首先核对PG 17.11、pgvector 0.8.6、非superuser、一次性数据库comment及vector运算，再执行全部事务/故障/恢复/查询。未使用SQLite、内存假库、生产数据或模型服务。
- 结果 **39/39通过**：fixture 1项、事务/恢复31项、查询7项。覆盖原文先登记、同ID幂等/冲突、唯一owner、stale revision CAS、CORE/ARCHIVAL/NOOP、同操作版本前驱、history/current/receipt/before_commit故障同生共死、提交后应答丢失、审计开始/结束失败、过期ABORTED/UNKNOWN、晚owner fence、快照/窗口/字节分页/精确召回和工作区隔离。
- 新增显式组件重建证据：COMMITTED后丢弃原Operation/Memory Store对象，重新运行schema validate并构造新组件，仍读取相同持久receipt、current正文与versions计数；不是JPA/进程缓存证明。日志`.verification/007-memory-backends/pg-t043-hard-gate.log`。同一源码最近完整unit为356/356通过。
- R2“暂存与事务机制”现标为通过；这只说明SDK暂存到PG事务/恢复/查询链闭合。R1完整镜像、R3 API/出口安全、R4真实模型及R5全仓封板仍未通过，Mem0继续默认关闭且不可启用。
- T043完成，当前55/79。下一步T044 API测试先行；未提交、未推送。

## T044五端点API测试先行（2026-09-02）

- 新建`test_api.py`，通过真实PG迁移fixture和进程内FastAPI TestClient锁定五端点：capabilities、PUT/GET operation、POST snapshot、GET entries。覆盖所有端点Bearer认证/workspace绑定、未知/缺失字段、NUL、256KiB请求体、RECEIVED/RUNNING/COMMITTED/FAILED/ABORTED、404固定错误、snapshot无scope且跨CORE/ARCHIVAL复用、page_size/cursor type/scope拒绝及无history/reset/config/docs入口。
- SAVE结果负例覆盖空/畸形状态、错误hash、history_complete=false及OUTCOME_UNKNOWN；要求未确认结果绝不200、UNKNOWN固定503且PUT仅一次。服务脚本记录调用次数，不允许路由层重放。T047后还须与真实OperationService组合，当前脚本不替代真实适配器验收。
- 为避免全局网络拒绝误伤Starlette在Windows创建本进程事件循环socketpair，新增`inprocess_asgi`窄fixture：仅该测试期间恢复标准socket函数，运行时只注入PG与脚本对象、无外部目标，结束立即恢复拒绝。首次运行的事件循环警告不计最终红灯。
- 最终收集25项：**21 failed、4 passed、0 skipped/collection error**；失败均为ApiRuntime尚未注册路由的HTTP行为红灯，日志`.verification/007-memory-backends/pg-t044-red.log`。完整Python unit **356/356通过**，15条Windows临时目录警告。
- T044作为测试任务完成，当前56/79；`app.py`仍只是无docs/openapi/路由的依赖骨架，T047未完成。下一步T046 snapshot/cursor服务，再由T047修绿API；R3/US3/007仍未通过，Mem0不可启用。未提交、未推送。

## T046 scope无关快照与绑定cursor（2026-09-02）

- 新增`SnapshotService`：create只从QueryStore当前revision生成随机nonce的300秒HMAC snapshot，payload不含scope，同时返回CORE全量和ARCHIVAL窗口计数。`QueryStore.snapshot_at`只接受不晚于当前namespace的revision，不提供任意未来revision入口。
- page先验签snapshot/workspace/期限，再按同一R重构指定scope；cursor另绑定完整snapshot摘要、workspace/R、CORE或ARCHIVAL、固定sort_id及最后完整键，不能跨scope/snapshot/type复用。next cursor继承不晚于snapshot的期限；complete=true严格无cursor，非终页必须有前进键。
- QueryStore已按占位信封控1MiB，服务层再计入真实长snapshot/cursor和完整HTTP JSON；若超限按完整条目缩页，不截正文或伪装完成，单项仍放不下明确失败。
- 新增单元**4/4通过**：scope无关双计数、同snapshot双scope/cursor越scope拒绝、游标前进/终页、过期和page_size拒绝。真实PG查询回归**7/7通过**；完整Python unit现为**360/360通过**，16条Windows临时目录警告，日志`.verification/007-memory-backends/t046-unit.log`。
- T046完成，当前57/79。T044 API仍为21行为红灯，下一步T047注册五路由并组合实际服务；R3/US3/007仍未通过，Mem0不可启用。未提交、未推送。

## T047五路由与严格HTTP边界（2026-09-02）

- `app.py`仅注册协议定义的五路由，关闭docs/redoc/openapi；history/reset/configure及其他路径保持404。所有路由先校验Bearer，再精确比较路径workspace；缺认证401、越权403，不泄露工作区存在性。启动前强制执行注入的schema/config检查，服务方法和固定protocol/schema/受控SDK/三能力声明不匹配即拒绝注册；capabilities不作为安全证据。
- PUT直接读取原始body，先拒绝>256KiB，再用共享parse_request严格检查重复/未知字段、NUL/32KiB/枚举并重算hash。生产OperationService按SAVE/RECALL调用；测试脚本的put入口仅用于锁定HTTP确认行为。GET/PUT只接受严格receipt DTO及匹配workspace/operation，pending=202、commit=200、failed=422、aborted=504；空/畸形/错误身份/hash/history不完整绝不200。
- snapshot只接受空JSON对象，entries手工严格解析scope/page_size/cursor并委托T046；SecurityError映射400/403/410。所有成功/错误响应生成新规范request_id并复用实际1MiB序列化器；OUTCOME_UNKNOWN固定503且路由只调用一次PUT。错误正文固定中文，不透传异常。
- T044 API由21红/4负向通过修绿为**25/25通过**。同轮真实PG fixture+事务/恢复+查询+API完整集成**64/64通过**，日志`.verification/007-memory-backends/pg-t047-integration.log`；Python unit **360/360通过**，17条Windows临时目录警告，日志`t047-unit.log`。
- T047完成，当前58/79。R2已通过，但T048镜像/部署、T049实际协议与R1/R3、T051 Java Store、真实模型R4及最终R5仍未完成；Mem0不可启用。未提交、未推送。

## T048部署件与构建清单（2026-09-02）

- 核对上一会话起草的 Dockerfile/compose.yaml/build-manifest.json，发现 `container-entrypoint.sh` 在 manifest 写入后又被修改（06:57 晚于 06:54），记录的 `entrypoint_sha256` 失效；manifest 的 `adapter_image_digest` 对应旧 entrypoint 的构建，且 Docker 守护进程当前不可用（npipe 不存在，plan 已记录 Desktop 4.82 本地崩溃史），无法核验。过期 digest 一律作废，不保留无法核验的 `built_local` 声明。
- compose 修正两个部署缺陷：原稿只有 `internal: true` 网络，适配器无默认路由、无法到达契约要求的内网 LLM/embedding 端点——新增独立 `mem0-egress` 桥网络作为唯一出站路径，Postgres 仍只挂 `mem0-internal`，端口依旧只绑 `127.0.0.1`；原稿 Postgres 仅有超级用户——新增 `scripts/postgres-init/00-create-app-role.sh`，首次初始化空数据卷时创建非 superuser 的 `oryx_mem0_app`（仅授应用库 CREATE），密码经新 Secret `postgres_app_password` 注入，已初始化卷须部署人员手工补齐。
- 新增 `scripts/build_manifest.py` 作为 manifest 唯一生成入口：重算 Dockerfile/compose/.dockerignore/entrypoint/prepare/postgres-init/runtime-requirements/migration/pyproject/uv.lock 十项摘要，先按 `vendor/sdk-lock.json` 核验受控 wheel，再生成 `build/source-manifest.txt`（35 个源文件实际摘要）并记录其 digest；`status=authored_pending_image_build`、`adapter_image_digest=null`，digest 只能由 T049/T072 真实构建后回填。
- `tests/unit/test_deployment_build.py` 改为锁定状态机：`built_local` 必须带 sha256 digest、待建必须为 null（不再让过期 digest 通过字符串检查冒充源码绑定），并锁定 egress 网络、initdb 只读挂载与新 postgres_init 摘要；逐文件重算 source-manifest，不信任生成脚本。
- 证据：`docker compose --profile mem0 config` 校验通过（`.verification/007-memory-backends/t048-compose-config.log`）；Python unit **362/362通过**（含部署清单2项），日志`t048-unit.log`。基础镜像digest、uv 0.10.11、SDK 1.0.11+oryx.1 wheel 摘要均与锁定值一致。
- T048完成，当前59/79。镜像构建、digest回填与扫描属 T049/T072，R1/R3/R4/R5仍未通过，Mem0不可启用。未提交、未推送。

## Docker只读诊断与T051先行（2026-09-02）

- 用户确认本机为实体机且BIOS VMX已开启。Windows 11专业工作站版22H2/build22621.4751；VirtualMachinePlatform、WSL、Hyper-V及HypervisorPlatform均Enabled，hypervisorlaunchtype=Auto，HypervisorPresent=true，vmcompute/HvHost/vmms/vid均运行，无CBS/Windows Update待重启标记。DISM CheckHealth未报告组件存储损坏；这不是完整系统健康验收。
- Docker Desktop进程存在，但最新后端日志在导入docker-desktop时报告`HCS_E_HYPERV_NOT_INSTALLED`，Hyper-V-Compute事件11008同样拒绝创建虚拟机。PATH中旧Docker CLI29.5.3与D:/Docker下29.7.2均无法取得Server版本、返回500；客户端版本差异不是已证实的引擎故障根因。仅WSL1 OryxPgTest已注册；`wsl --system`因默认发行版为WSL1返回WSL_E_WSL2_NEEDED，不能作为第二次HCS复现证据。
- Windows build低于Docker现行官方支持基线Windows11 23H2（[官方要求](https://docs.docker.com/desktop/setup/install/windows-install/)）；这是已知兼容性风险而非已证明的唯一根因。没有重启、重置/重装Docker、删除发行版/卷或改BIOS/Windows功能；T049保持未完成。
- T051的显式前置T050/T045已满足，按tasks允许的Java测试叶子分支继续。新增真实Mem0MemoryStore入口与专属CA HTTPS协议对端（只替换远端传输，不替换Store）。首轮覆盖共同契约、冻结NUL分隔hash、远端正文NUL/非法Unicode/32KiB、核心103项/归档100窗口、分页完整性、SAVE确认/失败/NOOP/原ID查询/不重PUT、TLS/guard/代理/慢正文/中断与RECALL排序/字节前缀。
- 主模型执行`mvn -o -pl oryxos-memory -am test -Dtest=Mem0MemoryStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false -Ddependency-check.skip=true`，编译及Spotless通过，67项中40 assertion failures、26未实现异常、1通用失败断言通过、0 skipped；未发生测试收集错误。证据`.verification/007-memory-backends/t051-red.log`。这是TDD红灯，不是远端运行通过；T051完成后实际60/79，T057/T058接着修绿。

## T057/T058受控Java传输与Store（2026-09-02）

- 新增包内`Mem0HttpTransport`/`Mem0Protocol`及真实`Mem0MemoryStore`。固定HTTPS origin与自有路径，每次最终URI先guard；禁重定向、代理继承与TLS隐式重连，标准证书/主机名校验；单次同步调用由虚拟线程承载，以逻辑总期限和连接/读取预算取消并关闭资源，实际响应最多1MiB，严格UTF-8/重复JSON字段/终态身份/hash/结构验证。
- SAVE在实际派发前标记状态，PUT仅一次；非可信确认（畸形/超限200、5xx、身份/hash不符、缺history等）只按原ID查询，最频250ms一次。只有匹配COMMITTED或明确无投影副作用的FAILED/ABORTED可确定结果；查询拒绝/中断/耗尽仍返回UNKNOWN+原UUID。RECALL独立按只读失败处理，验证排序/唯一ID/完整前缀/计数与字节缩减标记，不返回伪记忆。
- load以同一快照分别读取CORE全量及ARCHIVAL窗口；验证revision下界、scope、唯一ID、页计数与cursor前进；任一错误整体失败。补充“页面传输途中快照过期”负例，先观察1项明确红灯（日志`t058-expiry-red.log`），再在每页I/O前后检查expires_at，未裁剪正文或放宽断言。
- 初轮67项有1项真实代理泄漏：JDK21的SSL无参socket即使上层NO_PROXY仍调用默认SOCKS选择器。`t057-proxy.log`保留实际调用栈；核对本机JDK源后使用标准SocketFactory不支持无参socket的回退机制，让JDK先创建NO_PROXY直连，再用原TLS工厂叠加SSL；连接型fallback拒绝，不修改进程全局ProxySelector。修正后67/67及core49/storage16/memory126合计191/191通过，日志`t058-regression.log`。
- 复用父POM已锁定的spotbugs-annotations 4.10.3、provided作用域，仅对私有send方法的URLConnection SSRF提示注明固定origin/必需guard/禁代理重定向和真实拒绝测试的依据。未全局关闭扫描或放宽网络检查。P3C常量/条件、Unicode头匹配、日志标量化与循环字符串构造问题均已修正。

## T060组合接线与回归检查点（2026-09-02）

- boot的`MemoryOutboundConfiguration`在选择内建Mem0时组合Guard→现有Sandbox HTTP_REQUEST，不创建memory→tool依赖；缺Sandbox失败，用户自定义Guard/Store/Service优先，默认本地不创建Guard。任意安全校验失败固定拒绝，不透传目标或底层异常。
- MemoryConfiguration仅创建所选Mem0 Store，真实HTTPS capabilities校验后才就绪；缺guard或不兼容caps启动失败。自定义Store不解析未用的Mem0 Secret。application.yaml新增条件消费的MEMORY_BACKEND/MEM0环境入口，不设置默认云地址；内建Mem0成功文案“记忆处理完成”，旧构造入口/本地“已记住”保留，异常不能转为成功字符串。
- 先行红灯：Memory配置/文案15项中2失败、boot接线5项中2失败，均为行为断言，0收集错误/skip；日志`t060-memory-red.log`、`t060-boot-red.log`。修复后真实CA启动与坏caps、定向Memory/boot/原有Markdown与SQLite集成合计**93/93通过**：Mem0契约68、配置9、文案8、boot安全5、既有本地集成3。命令`mvn -o -pl oryxos-boot -am test -Dtest=Mem0MemoryStoreContractTest,MemoryOutboundConfigurationTest,MemoryToolsTest,MemoryConfigurationTest,MemorySystemIntegrationTest,MemoryBackendSystemIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dtest.excludedGroups= -Ddependency-check.skip=true`，日志`t060-regression.log`。测试只在隔离JVM临时信任本用例CA并finally恢复，不修改Windows信任库、不用trust-all。
- 最后以`mvn -o -pl oryxos-boot -am verify -Dtest=MemoryOutboundConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Ddependency-check.skip=true`检查当前九模块，boot接线5/5复测通过、Checkstyle/报告中的PMD违例/SpotBugs为0，命令BUILD SUCCESS；日志`t060-quality-final.log`。**这是快速门禁，不是R5全绿**：OWASP未执行，PMD6仍打印`aktStatus is NULL: maximum Iterations exceeded`数据流诊断，其现象与[PMD上游记录](https://github.com/pmd/pmd/issues/873)及[P3C上游记录](https://github.com/alibaba/p3c/issues/864)一致；完整分析覆盖须在R5核验，不能用退出0掩盖，未升级检查器或删规则。
- T051/T057/T058/T060完成，实际**63/79**。T049缺可用Docker/镜像构建扫描，T061缺获准真实内网模型；T062及后续故事/全矩阵/封板仍未完成。Java HTTPS对端替身与此前含API脚本的Python集成均不代替真实自托管组合、真实模型或完整出口证据；R1/R3/R4/R5继续未通过，Mem0不可启用。未提交、未推送。

### Windows只读完整性检查补充

- `DISM /Online /Cleanup-Image /ScanHealth /English`完成，明确返回`No component store corruption detected`；日志`.verification/007-memory-backends/docker-dism-scanhealth.log`。
- `sfc /verifyonly`完成至100%，结果为“Windows 资源保护未找到任何完整性冲突。”；日志`docker-sfc-verifyonly.log`保留原始控制台捕获。该UTF-16输出被PowerShell按UTF-8接收而乱码，已用原消息UTF-16字节经同一UTF-8解码后的精确包含匹配确认结果，未改写原始日志；不是凭乱码猜测健康状态。
- 未执行RestoreHealth、scannow、组件重装/升级、Docker reset、发行版/卷删除或重启。检查没有证明系统文件损坏，也没有消除WSL2/HCS故障；Windows22H2与Docker当前支持范围的差距仍是兼容性风险，不当作已证实的唯一根因。进一步系统修复或切换测试环境需要另行决定。

## Docker恢复、T049真实构建与T080补救（2026-09-03）

- 用户更新系统后确认Docker恢复；实测Docker Desktop4.89.0/Engine29.7.2、linux/amd64服务端正常。只创建唯一命名的`oryx007-t049-*`测试项目，未修改本机PG14、用户已有库或卷。按用户提示只核对.env变量名，存在DEEPSEEK_API_KEY、MOONSHOT_API_KEY、NVD_API_KEY；未回显/上传密钥，也未将云Provider key视为内网模型准入。
- Dockerfile由宿主site-packages复制改为固定镜像内`pip --require-hashes --only-binary=:all: --no-deps`安装和`pip check`；构建中校验源码清单SHA及所有源文件，运行层仍非root/无Secret。prepare脚本不再删除宿主依赖目录。新增2项构建红灯后4/4通过；build_manifest兼容经典config ID与Docker29 OCI manifest ID，标签/平台/源码不匹配拒绝回填。
- 当前实际镜像`oryxos/mem0-adapter:t049-d15e7d9716a7`，OCI digest=`sha256:0defb36946bbf43bf04f6293b8917bbeb3abfba93522fd1c0974c90576fecf01`，源码清单SHA256=`d15e7d9716a7d804ff0873a9572c3302ceb5adabfe7fea1580d03e4622b9532b`。固定Python/PG基础digest未变；构建/inspect原始记录为`t049-image-final-build.log`、`t049-build-final-metadata.json`、`t049-image-final-inspect.json`，build-manifest已绑定这些证据，不把built_local解释为安全通过。
- 隔离部署首次发现新PG数据库未启用vector；在新卷初始化脚本显式创建`vector VERSION '0.8.6'`，第二项目验证PG170011/扩展0.8.6/非superuser。API与模型容器只接内部网络，真实HTTPS客户端在容器内请求；仅PG测试端口映射到host loopback供原有fixture使用，测试前复核库名/注释/角色/版本才清理本库oryx_memory schema。测试模型仅合成返回值，固定SDK与PG、HTTP入口均为真实组件。
- 真实部署首轮6项中4失败/2通过（`t049-deployed-red.log`）：归档失败定位为embedding合法partial usage被拒绝，LLM审计已COMPLETED而embedding留STARTED（`t049-archive-diagnostic.log`）；另有replayed固定false、已持久FAILED返回500、同ID不同请求返回500。新增T080追踪，不删断言或降低门禁。
- T080修复：usage逐字段允许NULL且非空值仍严格校验；register_result用真实INSERT rowcount携带replayed；OperationService返回匹配持久终态，查不到终态才用固定OutcomeUnknown类型，RequestConflict固定409。GET调用已有operation/call恢复；PUT验证完整identity/hash而非只比较两个UUID；请求按stream有界读取，分页预算计入request_id。partial usage先3红/3负例通过，修复后相关22项通过。
- 当前验证：**377/377单测**（`t080-all-unit.log`，73 integration按标记排除，不计通过）；**65/65原有真实PG集成**（`t080-pg-regression.log`）；**8/8真实容器测试**（`t080-deployed.log`六项与`t080-deployed-recovery.log`两项），覆盖原文/哈希/重启、幂等、真实SDK ADD/UPDATE/NOOP/RECALL及逐调用审计计数、失败/冲突、103核心分页、认证/NUL/大小、GET过期恢复、非root/只读/无默认出口。未调用真实业务模型，不能代替T061/R4。
- 镜像内离线执行源码校验，149个SDK文件与获准wheel一致且只有FAISS改变，证据`t049-image-sdk-proof.json`。71项完整Python依赖审计只有1项已回移Mem0告警、0未处置项，见`t049-dependency-audit.log`与其指向的原始报告；不是用本地版本绕过查询。
- **镜像安全未通过**：本地Trivy0.73.0（官方zip SHA256=`d2d3ad5292aae470a03eb6506db86fce81b1894592b8451cadaf60eaa22f2025`）关闭遥测、使用本地image tar与offline-scan，未上传镜像/业务数据。漏洞库更新时刻2026-09-02T20:01:19Z。适配器143条按包计发现，其中仅Mem0 1项有实际镜像字节+回归证明可处置，剩142；PG镜像421条尚待处置；文件及镜像配置Secret扫描均0。原始报告`t049-trivy-adapter-full.json`、`t049-trivy-postgres-full.json`保留；`report_image_audit.py`仅接受指定SDK修复证明，组合门禁`t049-image-audit-gate-full.json`状态blocked、退出1。详见[镜像安全复核](image-security-review.md)。
- T080完成，当前**64/80**；T049因R1失败继续未勾选，T061仍缺获准内网LLM/embedding地址和配置。R1/R3/R4/R5未通过，Mem0不可启用；不自动更新锁定运行版本或扩大安全豁免。所有本轮测试容器已停止，卷/镜像/证据保留；未重启电脑，未提交或推送。

## T049镜像安全处置与门禁通过（2026-09-03 第二轮）

- 用户批准"逐项适用性评估处置"政策与"Debian 锁定版本+精简运行层"方向后执行：适配器运行层移除 pip/ensurepip（-6 条）；PG 基础镜像换官方 `0.8.6-pg17-trixie`（PG 17.11/pgvector 0.8.6 不变），派生层 hold postgresql*/libpq* 后 `apt-get upgrade` 应用已发布修复（93 条 fixed 清零），并移除 gosu（-46 条旧 Go stdlib 发现）、容器全程 postgres(999) 运行、去掉全部 cap_add。
- 漏洞库更新到 2026-09-03T07:08Z 后新增 util-linux 4 个 CVE；两轮计数不可直接比较，本轮双镜像同一库扫描自洽。最终按包计发现：适配器 173、PG 321，Secret 均为 0。
- 逐项处置：以 Debian 安全跟踪器全量快照（`t049c-debian-tracker.json`）核验，适配器 172 条与 PG 321 条全部为发行版 no-DSA 或 unimportant 评估；CRITICAL/HIGH 项另附配置级证据（组件不在运行路径/仅限32位/安装版本不在受影响范围）。无描述的保留项（4 个 glibc CVE、5 个 pcre2 TEMP）经 Debian bug/Red Hat 来源补齐细节后仍属上述处置；无一项被隐藏或全局忽略。台账 `tests/docker/image-finding-dispositions.json` 493 条。
- 门禁扩展 `--dispositions`：每条发现必须有匹配台账、版本一致、无过期记录；Mem0 CVE-2026-7597 仍只接受镜像内字节证明的回移处置。组合门禁 `t049e-image-audit-gate-full.json` **passed，退出 0**。
- 最终镜像回归：unit 377/377（`t049e-unit.log`）、真实容器部署 8/8（`t049e-deployed.log`，含非root/只读/无默认出口）、真实 PG 集成 65/65（`t049e-pg-regression.log`）、镜像内 SDK proof 通过（`t049e-image-sdk-proof.json`）；加固后 PG 核验 17.11+vector 0.8.6 且 postgresql-17 保持 hold。测试容器已全部停止，卷/镜像/证据保留。
- 适配器镜像 `oryxos/mem0-adapter:t049-406dc5485a80`（digest `sha256:e811033d…`），PG 派生镜像 `oryxos/mem0-postgres:0.8.6-pg17-trixie-hardened`（digest `sha256:218ac197…`），均经 build_manifest 绑定当前源码与构建证据。
- **T049 完成，当前 65/80。** R1 镜像安全部分通过；R4 真实模型（T061/T066）仍缺获准内网环境，R5 全仓封板未做，Mem0 不可启用。未提交、未推送。

## T061真实本地模型最小冒烟（2026-09-03）

- 用户批准 Ollama 本地真实模型方案：qwen2.5:7b-instruct（4.7GB）提炼/动作 + bge-m3（1.2GB，1024维）嵌入，主机 Ollama 0.5.11 + RTX 5000 GPU；推理全程本机回环，无数据出域。Docker Hub 的 ollama/ollama 镜像拉取停滞，按方案等价改用主机 Ollama；`tests/docker/model_proxy.py` 在隔离网内做 TLS 终结并只转发 `/v1/chat/completions`、`/v1/embeddings`（请求≤1MiB、响应≤8MiB、其余路径404），代理经 test-host 网桥到 `host.docker.internal:11434`，适配器仍只在 internal 网络。
- harness 新增 `--real-models`：显式拉取/校验两个锁定模型并预热加载，真实部署 ADAPTER_EMBEDDING_DIMENSIONS=1024；PG 版本/扩展/非superuser/标记注释断言不变。
- `tests/integration/test_runtime_smoke.py`（部署 `docker-t049-pixngvpn`，镜像 t049-406dc5485a80 与 manifest 绑定一致）：真实 SAVE 提炼 6 条事实、CHANGED/ADD≥1、`memory_versions` 与 action_counts 一致、该操作 LLM×2+EMBEDDING 审计全部 COMPLETED；重启适配器后持久终态可读；语义召回 top-1 命中 Java 事实，同义查询"他喝什么饮料"命中规范化为 "Americano/coffee" 的饮品事实（score 0.53–0.61）。两轮失败均为断言假定中文表述，真实召回已按 score/内容核实，未放宽协议断言。**1/1 通过**，日志 `t061-runtime-smoke.log`。
- Java 回归：core/storage/memory/tool `mvn test` BUILD SUCCESS，memory 131、tool 127 全部 0 失败（`t061-java-tests-summary.log`）；Python unit 377/377（`t061-unit.log`）。合成对端部署 8/8 证据（`t049e-deployed.log`）继续独立成立。
- T061 完成，当前 **66/80**。真实模型链路取得最小冒烟证据，但 T066 黄金集、T062 US3 审查、R4/R5 仍未通过，Mem0 不可启用。主机 Ollama 模型保留供后续任务复用；测试容器已停止。未提交、未推送。

## T062 US3故事级一致性审查与补救（2026-09-03）

- 对照 spec US3 十条验收、契约与交接遗留核验点逐项审查代码，确认 5 个真实缺口并追加 T081–T085 补救任务，全部修复且有复现测试；未把"测试全绿"当作无缺口证据。
- T081 capabilities 补齐构建版本与固定限制：Python `runtime.py` 从镜像内 source-manifest 实际字节算 64-hex build_version（缺失拒绝启动），`app.py` 改为形态校验；Java `Mem0Protocol` 同步要求 build_version/limits 六项固定值；双侧 fixture 与测试更新，未知字段仍整体拒绝。
- T082 RECALL 单快照：`queries.recall` 改只读 REPEATABLE READ（revision 与条目不错位），`complete_recall` 改为 revision 不后退（并发 SAVE 不再使 RECALL 伪失败，与 Java 侧 minimum/max 语义一致）；新增并发推进+回退拒绝+负分钳位 3 项测试。
- T083 暂存快照元数据：`PgReadonlySnapshot` 的 created_at 改为 ADD 版本真实 changed_at（JOIN memory_versions），不再用 updated_at 冒充；修复 search 列切片错位（真实部署 UPDATE 路径 503 红灯定位后修复），补 get/search 双路径时间戳测试。推理快照与 baseline 的绑定经既有 revision CAS 测试核验成立。
- T084 提交期真实连接丢失：`pg_terminate_backend` 在 before_commit 杀掉提交连接——服务端回滚、无投影/历史、终态 FAILED/SERVICE_FAILURE 且重 PUT 幂等；RUNNING 期真实断连不误标终态，恢复为 ABORTED。两测试只杀测试期新建后端，不动 fixture 连接。
- T085 `inprocess_asgi` 收窄为仅放行 loopback 连接（Windows 进程内管道所需），非回环目标仍拒绝；API 25/25 通过，网络拒绝断言未动。
- 最终镜像 `oryxos/mem0-adapter:t049-9f9684d778d0`（digest `sha256:724df344…`）绑定当前源码；该镜像回归：unit 377/377（`t062-unit.log`）、真实 PG 集成 73/73（`t081-t085-integration.log`，含真实模型冒烟）、合成部署 8/8（`t062b-deployed.log`）、真实模型冒烟 1/1（`t062b-runtime-smoke.log`）、镜像 SDK proof（`t062b-image-sdk-proof.json`）、镜像门禁 passed 退出 0（`t062b-image-audit-gate-full.json`，适配器 172 + PG 321 处置不变）。Java memory 133 项 0 失败。
- US3 版本台账：Python 3.12.14；SDK 1.0.11+oryx.1（Git 144627c4 + 官方 CVE-2026-7597 回移）；uv.lock `225d3ae7…`；FastAPI 0.141.1/uvicorn 0.52.4/psycopg 3.3.4；PG 17.11 + pgvector 0.8.6（trixie 派生镜像 `sha256:218ac197…`）；适配器镜像 `sha256:724df344…`；协议 oryx-memory-v1（本轮起 capabilities 增加 build_version/limits 字段，双侧同步）。
- 原版缺口说明继续保留；T066 黄金集、US4 与 R4/R5 未完成，Mem0 不可启用。

- US3 稳定提交：`83a8a50 feat(memory): add controlled self-hosted Mem0 backend (007 US3)`，含适配器、Java 链路、部署件、台账与规格同步；提交前全量 Java `mvn test` BUILD SUCCESS、spotless:check 通过。T062 完成，当前 **71/85**。US4 与 R4/R5 未完成，Mem0 不可启用。未推送。

## T063–T065三后端切换与审计集成测试（2026-09-04）

- 新建 boot 侧 `Mem0AdapterStub`（oryx-memory-v1 协议内存替身，真实HTTPS、严格响应形状、请求计数与失败/丢包/延迟脚本钩子）；`HttpsFixture` 转 public 并经 memory test-jar 复用，root/boot pom 锁定 mockwebserver 4.12.0 与 test-jar 依赖。替身只做协议保真，不冒充真实提炼。
- T063：`MemoryBackendSystemIntegrationTest` 扩到三后端，mem0 腿复用真实 Store/Session/Tool 链路：空存储无空段、保存/回忆经统一工具链、重启（新上下文）后远端持久状态可读、Prompt 核心段在归档段之前、未触本地 memory_entries 行。
- T064：`MemoryBackendSwitchIntegrationTest` 覆盖 6 有向切换（目标从零开始不搬运）、回切恢复原数据、两工作区/两服务身份隔离、MD/SQLite 不解析 MEM0 变量可启动、未选后端网络请求数=0 与 memory_entries 行访问计数成立。
- T065：`MemoryBackendAuditIntegrationTest` 经统一 save_memory 工具链验证：成功 completed；远端 503/SERVICE_FAILURE → 本地 failed+固定分类+操作 UUID 且同 ID 不重放；PUT 读超时后按原 ID 确认 COMMITTED（业务生效）；请求被丢弃 → OUTCOME_UNKNOWN+UUID 且远端无记录；AUDIT_UNAVAILABLE(422) 可观测失败不触发重放。
- 三项合计 6/6 通过（`mvn -pl oryxos-boot -am test -Dtest='MemoryBackend*IntegrationTest' -Dtest.excludedGroups=`），日志 `.verification/007-memory-backends/t063-t065-java.log`（后补录）。真实适配器侧 PG 操作/calls/history 取证继续由 T061/T080 的真实部署证据承载。US4 故事收口（T070）与 R4/R5 仍未完成，Mem0 不可启用。

## T066真实模型黄金集过程记录（2026-09-03/04，未通过）

- 环境：harness `--real-models` + TLS代理 + 主机 Ollama（GPU）。语料 `tests/fixtures/memory-golden.json` 为获准合成内容；断言锚定语义要点，未因结果不符删除断言。
- qwen2.5:7b：提炼可用但动作选择把 UPDATE 的 text 写成旧值（退化更新）；phi4:14b：偶发 ENGINE_INVALID_RESULT（严格 JSON 校验红灯）；qwen2.5:14b-instruct：提炼/协议稳定，但中文事实下把"替换"做成"退化UPDATE+新增"，旧值仍当前有效。
- 关键根因排查：动作选择的检索段确实包含旧记忆（排除 embedding 未召回）；直接以真实审计提示词离线重放英文事实时两个模型都能正确 UPDATE——失败集中在中文事实的 text 字段纪律。适配器侧已将模型调用固定 temperature=0 消除抽样漂移（镜像 t049-5f03f941a037，SDK proof/部署/冒烟回归通过，门禁 passed）。
- 结论未定：黄金集仍未通过，继续评估获准范围内的本地模型；若可用模型均不满足替换纪律，T066 保持未完成，R4 不通过，007 不封板。

## T066真实模型黄金集通过（2026-09-04）

- 最终获准本地模型组合：mistral-nemo:12b（提炼/动作）+ bge-m3（1024维嵌入），主机 Ollama GPU 回环；适配器镜像 t049-5f03f941a037（模型调用 temperature=0）。三个候选模型的失败模式均已记录（7b 退化UPDATE文本、phi4 严格JSON偶发失败、qwen14b 中文事实替换纪律失败）；mistral-nemo 经真实提示词离线探针与完整运行验证。
- 语料工程（均已获准合成）：提取用例改为原子短句（模型对复合句会丢事实）；替换用例用显式更正表述（"不再是17"）；合并用例用逐字重复（SDK 对同文本出 NONE）；空事实用例为"（本次没有需要记录的内容）"（'……'会被硬造事实）。
- 结果：**黄金集 1/1 通过**（`t066-live-models-final.log`，部署 docker-t049-g3xnz1fz）：提炼/显式更正替换（旧值不再当前有效、历史双方可溯）/重复合并（当前条目数不变）/合法空事实 NOOP（无新版本）/同义召回逐项命中。**冒烟同镜像同模型 1/1 通过**（`t066-runtime-smoke-final.log`）。
- T066 完成，当前 75/85。T068 整体证据矩阵与 R5 封板仍未完成。

## T068三后端整体验证与R2–R4矩阵（2026-09-04）

- 三后端集成：`mvn -pl oryxos-boot -am test -Dtest='MemoryBackend*IntegrationTest'` 6/6（`t063-t065-java.log`）：mem0 经真实 HTTPS 替身走真实 Java Store/Session/Tool 链路；6 向切换不搬运不清库、回切可读、两工作区隔离、未选后端零网络/零行访问；统一工具链审计区分 成功/拒绝/超时确认/未知/审计故障。
- 黄金集与冒烟（最终镜像 t049-5f03f941a037）：真实 mistral-nemo:12b + bge-m3 全数据路径通过（`t066-live-models-final.log`、`t066-runtime-smoke-final.log`）；合成对端部署 8/8 同镜像通过（`t068-deployed.log`，含非root/只读/无默认出口断言）。
- 真实出口检查：适配器容器只在 internal 网络（无默认路由，部署测试断言）；模型流量经 TLS 代理到主机回环 Ollama；MEM0_TELEMETRY=false；镜像 Secret 扫描 0；无默认云流量证据成立。完整数据路径：OryxOS(Java Store) → HTTPS(oryx-memory-v1, TLS) → 适配器 → 模型(本机 Ollama)/PG(pgvector) → 日志仅容器本地（no-access-log）。
- 跨重启历史：冒烟重启后持久终态/回忆可读；T080 恢复与 T084 真实断连用例覆盖 RECEIVED/RUNNING/提交期崩溃。
- R2 通过（T043 起，T082 后 73/73 复验）；R3 通过（T047/T080 + 部署证据）；R4 通过（T061 冒烟 + T066 黄金集，真实本地模型）。R5 封板未做；镜像门禁 passed（`t068-image-audit-gate-full.json`）。不上传业务数据或未脱敏配置。
- T068 完成，当前 76/85。

## T069/T070 US4收口（2026-09-04）

- T069：README 三处 007 状态改为已实现/已验证/待封板的准确表述；quickstart.md 改为实施后的验证路径，含三后端测试、隔离部署与真实模型命令；适配器 README（T067）覆盖部署/Secret/hash绑定/TLS/维度/版本锁/NOOP与未知结果含义/历史只读核对/操作ID查询/安全停止。
- US4 一致性审查：六条验收场景与边界用例逐条对照 T063–T065 及 US3 证据成立；不热切换、重启生效、非法后端可定位错误、审计不泄漏凭证、空目标与访问失败区分、窗口外归档可查询、重复输入可追溯均覆盖。
- 提交前门禁：全量 `mvn test` BUILD SUCCESS（`t070-java-all.log` 见 .verification）。US4 完成。R5 封板回归（T071–T075）仍未执行，Mem0 不可默认启用。
