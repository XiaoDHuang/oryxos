# 007 实施与验收台账

实施开始：2026-08-31；分支 `codex/007-memory-backends`。用户已确认进入实现，按75项任务顺序执行。

## 基线与保护范围

- 006归档基线：`3d60ee0`；不改写 `specs/006-memory/` 或删除既有回归断言。
- 九模块：core、provider、memory、tool、web、channel-cli、storage、cli、boot；不新增Maven模块。
- 保持 `MemoryService.buildContext(Session,int)`、`remember(String,MemoryScope)`、`recall(String)`，两个MemoryTools方法及旧LongTermMemory构造入口。
- 既有测试：LongTermMemoryTest、MemoryServiceImplTest、MemoryToolsTest、MemorySystemIntegrationTest，以及core Prompt/ToolExecutor、storage Session/审计和tool注册/适配回归。
- 开始时未提交变更均属前序007治理/设计：四份事实源、AGENTS、宪法及模板、课程技能/21–22课件、README、feature选择、范围决议和007规格/计划/任务。开始时无应用代码/POM/CI改动；这些已批准文档作为007前置基线保留，不混入无关变更。
- 006已有228默认测试及显式集成、完整verify/OWASP证据仅为历史，不计007验收。
- 主模型编写代码及测试；迭代验证由主模型执行，最终T073回归仅由Spark运行命令，禁止其改源码/测试。

## 实施前检查

check-prerequisites通过；requirements.md为16/16，无未完成项；无扩展hooks。Specify CLI维持0.14.2。

## 故事证据

| 增量 | 状态 | 命令/结果/证据 | 稳定提交 |
|---|---|---|---|
| US1 默认Markdown兼容 | 完成 | 94项单测+1项真实重启集成、快速质量检查通过 | `2a63e58` |
| US2 SQLite | 完成 | 109项迭代单测通过，最终55项受影响测试（含3集成）通过，静态快速门禁通过 | 本条记录随US2稳定提交 |
| US3 固定SDK/受控Mem0 | 未执行 | 待执行 | 未提交 |
| US4 三后端整体 | 未执行 | 待执行 | 未提交 |

## 运行/封板门禁

| 门禁 | 状态 | 必需证据 |
|---|---|---|
| R1 依赖可重复与安全 | 未执行 | 完整锁图、SDK摘要、镜像digest、Python/镜像扫描 |
| R2 暂存与事务 | 未执行 | 固定SDK机制、真实PG故障/原子性/恢复 |
| R3 安全接线 | 未执行 | 凭证、guard、TLS、scope和实际出口拒绝 |
| R4 真实后端 | 未执行 | 内网模型/embedding、黄金集、历史和六向切换 |
| R5 全仓质量 | 未执行 | 显式集成、无跳插件verify、Python与镜像证据 |

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
