# Tasks: ReAct 循环(Agent 大脑循环)

**Input**: Design documents from `/specs/002-react-loop/`

**Prerequisites**: plan.md、spec.md、research.md、data-model.md、contracts/react-api.md

**Tests**: harness 先行——测试任务先于对应实现任务;方法名英文,课件中文原名进 `@DisplayName`。

## Phase 1: Setup

- [X] T001 改造 oryxos-storage/src/main/resources/db/schema.sql:`tool_invocations` 增 `success INTEGER NOT NULL`、`error_message TEXT` 两列,保留既有 status/result/error/token_cost 列

## Phase 2: Foundational(前向形状与端口,阻塞所有 Story)

- [X] T002 [P] 建 oryxos-core/src/main/java/com/oryxos/core/tool/ToolResult.java:record(toolName/success/content/errorMessage)+ `ok`/`fail` 静态工厂
- [X] T003 [P] 改造 oryxos-core/src/main/java/com/oryxos/core/tool/OryxTool.java(D4):加 `ToolResult execute(String argumentsJson)`;**同步给 16 节测试里的两个匿名桩类(ProviderServiceTest/ToolSchemaAdapterTest)补 execute 实现**,保持编译绿
- [X] T004 [P] 建 oryxos-core/src/main/java/com/oryxos/core/session/Session.java:内存版,id/profileName/messages(Spring AI Message),`append(UserMessage)`/`append(AssistantMessage)`/`appendToolResult(ToolResponseMessage)`
- [X] T005 [P] 建 oryxos-core/src/main/java/com/oryxos/core/session/SessionManager.java(接口,`save(Session)`)+ InMemorySessionManager.java
- [X] T006 [P] 建 oryxos-core/src/main/java/com/oryxos/core/react/LlmGateway.java:`ChatResponse chat(String sessionId, Profile profile, Prompt prompt)`;改 oryxos-provider 的 ProviderService 加 `implements LlmGateway`(签名不动)
- [X] T007 [P] 建 oryxos-core/src/main/java/com/oryxos/core/react/ToolInvocationAudit.java:`record(sessionId, profileName, toolName, parameters, success, result, errorMessage, latencyMs)`
- [X] T008 [P] 建 oryxos-storage/src/main/java/com/oryxos/storage/audit/ToolInvocation.java(实体,对齐 data-model 列)+ ToolInvocationRepository.java + JpaToolInvocationAudit.java(实现 T007 接口,写库失败仅记日志)
- [X] T009 [P] 建 oryxos-core/src/main/java/com/oryxos/core/react/ProfileContext.java:ThreadLocal `set`/`current`/`clear`
- [X] T010 先写 oryxos-core/src/test/java/com/oryxos/core/context/ContextLoaderTest.java:改文件后下一次 build 立即读到新内容(无缓存回归);Skill 引用缺失抛 IllegalStateException;Bootstrap 缺失 WARN 不报错(断言加载继续且结果不含该文件)
- [X] T011 建 oryxos-core/src/main/java/com/oryxos/core/context/ContextLoader.java:`load(Profile)` 读 `.oryxos/` 根 bootstrap 文件 + `.oryxos/skills/<name>/SKILL.md`,每次重读无缓存

## Phase 3: User Story 1+2 - 循环本体 + 兜底/截断 (P1)

**Independent Test**: 无工具一轮收尾;有工具执行并回填进下一轮;永不收敛时恰好调 N 次强制停;历史超 N 截断;system 末尾含当前日期时间

- [X] T012 [US1] 先写 oryxos-core/src/test/java/com/oryxos/core/react/PromptBuilderTest.java:四段顺序正确;`systemPromptEndsWithCurrentDateTime`
- [X] T012b [US2] 在 PromptBuilderTest 补 `historyExceedingMaxTurns_isTruncated`(坑二回归:构造超 N 条历史,断言 prompt 只含末尾 N 条)
- [X] T013 [US1] 先写 oryxos-core/src/test/java/com/oryxos/core/react/ReActLoopTest.java:一轮无工具收尾;有工具调用则执行并回填进下一轮;单轮响应含两个 toolCalls 时按顺序执行(FR-008);每轮响应与工具结果累积进 Session(坑三回归)
- [X] T013b [US2] 在 ReActLoopTest 补关键回归 **`modelAlwaysCallsTools_forcedStopAtMaxIterations`**(@DisplayName「模型一直要调工具_转满最大轮数强制停」,verify chat 恰好 10 次、reply 含「达到最大轮数」,坑一回归)
- [X] T014 [US1] 建 oryxos-core/src/main/java/com/oryxos/core/react/PromptBuilder.java:四段拼装(system=identity prompt+ContextLoader 内容+当前日期时间;记忆接入位恒空;截断末尾 N 条;工具按 profile.tools 从工具表过滤),产出 core `Prompt`
- [X] T015 [US1] 建 oryxos-core/src/main/java/com/oryxos/core/react/ReActLoop.java:`run(Session, String, Profile)` 按课件骨架逐行落地(append → for 上限 N → build → chat 带 sessionId → 累积 → 无 toolCalls 返回文本 / 有则顺序执行回填)

## Phase 4: User Story 3 - 工具执行唯一收敛 + 成败审计 (P2)

**Independent Test**: 成功写 success=true;失败写 success=false 带原因、错误回填会话循环不崩;未知工具走失败路径

- [X] T016 [US3] 先写 oryxos-core/src/test/java/com/oryxos/core/react/ToolExecutorTest.java:成功审计 success=true;失败审计 success=false 带原因且异常不吞(返回 fail 结果含原因);未知工具 success=false
- [X] T017 [US3] 建 oryxos-core/src/main/java/com/oryxos/core/react/ToolExecutor.java:`execute(String sessionId, AssistantMessage.ToolCall)`——按名查工具表(Map 注入)、Sandbox 调用位注释(24 节接线)、执行、包装 ToolResult、经 ToolInvocationAudit 落库

## Phase 5: User Story 4 - 统一入口 + ProfileContext 不串号 (P2)

**Independent Test**: 处理期间 ProfileContext.current() 取到当前 Profile;process 抛异常后 current() 为 null;结束后 sessionManager.save 被调

- [X] T018 [US4] 先写 oryxos-core/src/test/java/com/oryxos/core/react/AgentServiceTest.java:`profileContextAvailableDuringExecution`;**`processThrows_profileContextStillCleared`**(@DisplayName「处理中抛异常_ProfileContext也必须被清掉」:run 抛 RuntimeException → process 上抛 → `ProfileContext.current()` 为 null);处理结束 Session 持久化
- [X] T019 [US4] 建 oryxos-core/src/main/java/com/oryxos/core/react/AgentService.java:`process(Session, String)`——profileRegistry.find(不存在报错)→ ProfileContext.set → try{ run;save }finally{ clear }

## Phase 6: Polish & 收尾

- [X] T020 全量验证:`mvn clean verify -Ddependency-check.skip=true` 全绿;16 节测试全回归;`grep -rn "sk-" oryxos-*/src` 无明文;`grep -rnE "CompletableFuture|Mono<|Flux<|newFixedThreadPool" oryxos-core/src/main` 为空

## Dependencies

- Phase 1 → Phase 2 → Story 3→4→5(T015 依赖 T006/T014/T017 之前置类型就绪;T017 依赖 T002/T003/T007;T019 依赖 T015/T005/T009)
- T003 触碰前序节测试桩(编译必要,非逻辑修改)

## Parallel Execution Examples

- Phase 2:T002~T009 八个 [P] 任务文件互不重叠,可并行;T010/T011 串行(先测后实现)
- Story 3 与 Story 4/5 的主测试编写可并行(不同文件),实现任务因共享类型须串行

## Implementation Strategy

1. Setup + Foundational 就位(T001~T011),类型与端口全部可编译;
2. US1+US2 打通循环 MVP(同两个 P1 story,共享 ReActLoop/PromptBuilder,两坑回归随实现立即转绿);
3. US3/US4 依次接入;每任务跑该模块测试,红即修;
4. T020 硬门禁收口。

## 注意(语法禁区)

避开增强 switch 的 `default ->` 等 P3C/ASM 解析不了的 Java 18+ 语法形态;`mvn verify` 红即任务未完成。
