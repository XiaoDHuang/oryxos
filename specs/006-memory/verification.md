# 第22节 Memory 实施证据

**分支**：`022-lesson22-memory`

**状态**：实现、回归、完整安全门禁与最终一致性分析均已完成；自动化封板条件满足。

## T001：H0 与规划门禁

- Specify CLI：0.14.2，主体开发期间不升级且未启用 extension。
- requirements checklist：16/16，进入实现前全部完成。
- 前序16–20节核心交付类：39/39存在；父POM仍为9模块。
- 第22节课件、TechnicalSolution、AiProgrammingGuide、AGENTS与feature plan均限定为单一`MEMORY.md`后端；无长期记忆表、后端配置、Mem0或向量范围。
- 用户已显式批准`MemoryService`/`MemoryScope`端口归core、实现归memory；事实源已在规划提交`2f93b02`同步。
- 实现前一致性复审：26/26 FR/SC有任务，34项任务格式全部有效，宪法冲突0。

## 验证记录

后续任务按实际命令、退出码和用例数追加；快速门禁与完整门禁分开报告。

### T002/T003/T006：依赖与端口

- `oryxos-memory`显式使用父POM锁定的`spring-ai-model:1.1.8`；`oryxos-tool`只新增内部`oryxos-memory`依赖，无新版本属性或外部依赖。
- dependency tree退出0，实际方向为`oryxos-tool → oryxos-memory → oryxos-core`，无core反向依赖；父POM模块仍为9。
- 本地`javap`已确认Spring AI 1.1.8的`ToolParam.required()`与`description()`存在。
- 首次test-compile仅因新文件行尾不符合Spotless失败；运行项目格式化器后复跑退出0，core/memory端口及package-info可编译。

### T007–T011：文件式长期记忆

- 先落可编译空壳后运行行为测试：LongTermMemory 14项全部按预期红（4 failure/10 error，均指向未实现行为）；InitCommand 2项中双分区模板1项按预期红、既有工作区不覆盖1项绿。
- 实现后LongTermMemoryTest 14/14全绿：旧格式迁移、双分区、核心完整、4000/4001边界、物理归档保留、跨实例现读、关键词、错误关闭、USER.md只读及同路径100并发保存均覆盖。
- InitCommandTest 2/2全绿：新工作区生成标准双分区，第二次init不覆盖已有内容。
- 两条指定Maven命令均退出0，指定suite实际执行非零用例；无真实用户`.oryxos`内容进入仓库。

### T012–T019：MemoryService、MemoryTools与统一注册

- 先以可编译空壳执行红态：MemoryServiceImplTest 5项中1 failure/4 error，MemoryToolsTest 6项中1 failure/4 error，均由未实现行为触发；ToolConfigurationTest新增可信Memory用例因普通插件默认guard拒绝而按预期红。
- 实现后指定命令退出0：LongTermMemory 14项、MemoryServiceImpl 5项、MemoryTools 6项、ToolConfiguration 11项，共36项失败/错误/跳过0。
- `MemoryTools`在registry freeze前显式加入trusted builtins；名称冲突仍失败关闭，既有普通Java插件默认拒绝及Sandbox测试未放宽。
- memory AutoConfiguration只装配固定`.oryxos`工作区的LongTermMemory、MemoryServiceImpl、MemoryTools；未新增配置键或旁路执行。

### T020–T026：Prompt主链与跨重启集成

- PromptBuilder/CoreEngine红态共10项中3项按预期失败：三参Memory装配缺失2项、Memory上下文未进入Prompt 1项；实现后PromptBuilderTest 8项、CoreEngineConfigurationTest 2项全绿。
- 既有PromptBuilder二参构造仍保留原历史截断行为；生产CoreEngineConfiguration只依赖core MemoryService端口，core测试类路径确认不含MemoryServiceImpl。
- 显式MemorySystemIntegrationTest执行1项并退出0：第一套Spring上下文/Session调用save后关闭，第二套上下文复用同一workspace/SQLite并创建不同Session，Prompt自动包含核心记忆，再调用recall。
- SQLite最终恰有`save_memory`/`recall_memory`各一条completed审计；两套上下文均清理ProfileContext，未修改OryxOsApplication或建立旁路。

### T027–T030：课件、回归、范围与H4

- 课件四个harness均存在且非空，无`@Disabled`；两个关键方法名和断言守点均落在LongTermMemoryTest。
- 九模块默认`mvn test`退出0：228项，失败/错误/跳过0；显式MemorySystemIntegrationTest另1项全绿。
- 交付物核对通过：core端口2个，memory实现3个加包私有配置，双分区初始化、Prompt/Tool接线均存在；父POM仍9模块。
- 生产源码未出现`memory_entries`、`memory.backend`、Mem0/embedding/semantic实现；Memory模块无USER.md引用，长期内容不进缓存。
- H4：Memory只访问构造时固定的受控workspace路径；LLM自动Tool执行仍为false；Memory Tool经ToolExecutor落两条真实SQLite审计；主源码无明文key；session_id仍只在JpaSessionManager.composeId拼接；core/memory无Reactor、CompletableFuture、自建线程池或SecurityManager。
- 快速门禁首次由MemoryService抽象方法缺少参数/返回Javadoc阻断，补齐后P3C通过；第二次由PromptBuilder可继承类的构造异常finalizer风险阻断。仓库无子类且扩展只走端口，因此将其收紧为final，保留两个公开构造签名且不使用SpotBugs suppression。
- 后续SpotBugs指出父目录返回值可空和scope Unicode case transformation；改为显式非空memoryDirectory字段与ASCII A–Z逐字符折叠后精确比较，research/contract/task同步更新，仍未增加suppression。

### T031：快速完整静态门禁

- 最终命令`mvn clean verify -Ddependency-check.skip=true`退出0，10/10 reactor项目成功，总耗时3分09秒。
- 默认测试228项，失败/错误/跳过0；Spotless、Checkstyle 0、P3C/PMD、SpotBugs/FindSecBugs 0全部通过。
- 日志`.verification/lesson22/fast-verify-r8.log`明确每个模块`Skipping dependency-check`，因此只称快速门禁，不代替T032。

### T032：完整安全门禁

- 未设置任何skip的`mvn clean verify`退出0，10/10 reactor项目成功，总耗时5分52秒；默认228项测试失败/错误/跳过0。
- Dependency-Check在父项目及九模块实际执行10次，`Skipping dependency-check`为0；boot最终报告扫描98个依赖，漏洞依赖0。
- Spotless、Checkstyle、P3C/PMD、SpotBugs/FindSecBugs与OWASP全部通过。OSS Index因上游要求凭证而未启用，NVD、RetireJS、KEV及既有suppression分析正常完成。
- 证据：`.verification/lesson22/full-verify.log`与各模块`target/dependency-check-report.{html,json}`。

### T033/T035：实现后一致性分析与补救追踪

- 最终analyze核对18项FR、8项SC、3个User Story、34项原任务与宪法8条原则：26/26需求有实现/测试映射，形式与端到端覆盖均100%；无宪法冲突、功能缺口、歧义、重复或未映射任务。
- 唯一MEDIUM项是静态门禁补救已有证据但缺独立任务ID；追加T035并同步PromptBuilder final、Windows原子移动回退、清理WARN、显式memoryDirectory与ASCII scope折叠。补救代码已包含在T031/T032最终全绿快照中，无需新增suppression或再改生产行为。

## T034：六项DoD与交付摘要

| DoD | 证据 / 状态 |
|---|---|
| 完整clean verify | 10/10 reactor成功；默认228项测试全绿；完整OWASP执行10次、98依赖中漏洞依赖0 |
| 课件harness | LongTermMemory14、MemoryServiceImpl5、MemoryTools6、PromptBuilder8项；四类均存在非空，关键两守点逐条保真 |
| 交付物 | core端口/枚举、memory实现/Tools/自动配置、双分区init、Prompt/Tool接线、双上下文integration全部存在；无新表/配置/模块 |
| 前序回归 | `mvn test`覆盖九模块共228项，失败/错误/跳过0；显式integration另1项全绿 |
| H4六项 | 受控固定Memory路径、双审计落库、无明文key、Session ID唯一拼接、无禁用异步/安全API、自动Tool执行关闭均已核对 |
| 一致性 | 26/26 FR/SC覆盖，35/35任务完成；无宪法冲突、功能缺口、未映射任务或未追踪补救代码 |

### 变更导读

- `oryxos-core`：新增`MemoryService`/`MemoryScope`端口；PromptBuilder通过端口组装Memory并保留二参兼容构造，CoreEngineConfiguration可选注入实现；新增/扩展core装配和Prompt测试。
- `oryxos-memory`：新增双分区LongTermMemory、MemoryServiceImpl、MemoryTools、包私有自动配置和imports，增加25项文件/服务/Tool测试；POM只声明已锁定Spring AI model。
- `oryxos-tool`：增加内部memory依赖，把MemoryTools明确加入trusted builtins；普通Java插件默认拒绝与冻结顺序保持不变，装配测试新增2项。
- `oryxos-cli`：InitCommand生成标准双分区Memory模板并保留已有工作区；新增2项初始化测试。
- `oryxos-boot`：新增1项双Spring上下文、双Session、真实SQLite审计integration。
- 前序文件触碰：PromptBuilder、CoreEngineConfiguration、ToolConfiguration、InitCommand及memory/tool POM均为本节课件明确集成点；未修改ReActLoop、ToolExecutor、Session schema、Provider或OryxOsApplication。

### 重点Review位置

1. `oryxos-core/.../memory/MemoryService.java`与`PromptBuilder.java`：端口反转、消息顺序及二参兼容入口。
2. `oryxos-memory/.../LongTermMemory.java`：双分区解析、物理归档不裁、路径锁、原子替换和Windows回退。
3. `oryxos-memory/.../MemoryTools.java`与`oryxos-tool/.../ToolConfiguration.java`：scope ASCII解析、可信内置注册与普通插件拒绝边界。
4. `oryxos-memory/.../MemoryConfiguration.java`：固定`.oryxos`默认实现和测试/嵌入覆盖能力，无新增配置键。
5. `oryxos-boot/.../MemorySystemIntegrationTest.java`：跨上下文/新Session恢复及save/recall各一条最终审计。

### 可复制验证命令

```powershell
# 默认九模块回归：预期228项全绿
mvn test

# 本节Memory单测：预期memory模块25项全绿
mvn -pl oryxos-memory -am test

# 双上下文集成：预期1项全绿、两条最终审计
mvn -pl oryxos-boot -am test "-Dtest=MemorySystemIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"

# 封板门禁：预期10/10模块、默认228项及全部静态/OWASP门禁通过
mvn clean verify
```

### 剩余人工项

- 配置真模型后，让Agent自行判断并调用`save_memory`，不要由测试直接替模型做决定。
- 退出真实进程并重新启动/新开会话，确认回复体感确实使用已保存偏好。
- 目检`MEMORY.md`写入正确scope且`USER.md`未变化。
- 目检真实运行的`tool_invocations`中save/recall各逻辑调用只有最终一条记录。

harness已判卷；以上四项等待用户在真实模型与实际工作区中人工验证。本轮不自动commit/push/package.sh。

最终代码快照另复跑显式integration 1项，退出0、两套上下文均真实关闭，证据为`.verification/lesson22/integration-final.log`。
