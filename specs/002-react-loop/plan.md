# Implementation Plan: ReAct 循环(Agent 大脑循环)

**Branch**: `017-lesson17-react-loop` | **Date**: 2026-08-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-react-loop/spec.md`

## Summary

交付 OryxOS 能力二「ReAct 循环」:`ReActLoop`(调度)+ `PromptBuilder`(四段拼装)+ `ToolExecutor`(唯一执行点)+ `AgentService`(统一入口编排)+ `ProfileContext`(请求级 Profile 上下文)+ `ContextLoader`(Bootstrap/Skill 供给),落 oryxos-core;`ToolInvocation` 实体 + Repository 落 oryxos-storage,`tool_invocations` 按课件补 `success`/`error_message` 两列。全部单测无网络,`mvn clean verify` 全绿为完成定义。

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**: Spring Boot 3.5.16、Spring AI BOM 1.1.8(`spring-ai-model` 消息类型,已在 core)、第 16 节交付物(ProviderService/Profile/Prompt/OryxTool)、SnakeYAML、SQLite + Spring Data JPA
**Storage**: `tool_invocations` 补列(`success`/`error_message`),手工脚本(已在 oryxos-storage/src/main/resources/db/schema.sql);Session 本节内存版,不落库
**Testing**: JUnit 5 + Mockito,全单测秒级;无集成冒烟(本节不碰网络)
**Target Platform**: 单体 fat JAR
**Project Type**: 多模块 Maven 功能切片
**Performance Goals**: 无本节专属指标
**Constraints**: 同步阻塞;无并行工具调用/委托/流式/压缩;审计成败必落;ProfileContext finally 必清
**Scale/Scope**: core 新增 6 类 + 2 个前向类型,storage 新增 2 类,5 个测试类

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

以 `.specify/memory/constitution.md` v1.0.0 八原则核查:

| 原则 | 判定 |
|---|---|
| I 自实现 ReAct loop | ✅ 本节正是本体;`ReActLoopTest` 钉死「未用框架封装」的循环行为 |
| II Spring AI 只用一半 | ✅ 只用其消息/选项类型;自动执行已在 16 节关闭且有回归 |
| III 同步阻塞 | ✅ 无 Reactor/CompletableFuture/自建线程池 |
| IV OryxTool 抽象 | ⚠ 需给 16 节最小接口加 `execute`(课件叙事已假设其存在)——停点确认 D4 |
| V Spring MVC | N/A |
| VI Sandbox 白名单 | ✅ 执行器留 `Sandbox.enforce` 调用位,注明 24 节接线(H4①允许) |
| VII SQLite+审计落库 | ✅ `tool_invocations` 补列 + 实体/Repository,成败双写 |
| VIII 安全合规 | ✅ 无密钥;工具参数/结果进日志前走消毒 |

## 关键设计决策(详见 research.md;D1~D6 为前向形状/改造点,tasks 停点逐项确认)

- **D1 `Session`(core,内存版)**:id/profileName/messages(Spring AI Message 列表),`append`/`appendToolResult` 累积;18 节升级 SQLite 持久化。出处:课件骨架 + AGENTS.md「US-2 的 Session 是内存版」。
- **D2 `SessionManager` 最小接口(core)**:`save(Session)` 唯一方法 + 内存实现;18 节完整化(`getOrCreate` 等)。出处:课件 AgentService 骨架调用 `sessionManager.save(session)`。
- **D3 `ToolResult` record(core)**:toolName/success/content/errorMessage;20 节扩充。出处:课件 ToolExecutor 产出。
- **D4 `OryxTool` 加 `ToolResult execute(String argumentsJson)`**:改 16 节接口。课件叙事「`OryxTool.execute` 的签名不带 Profile」已假设其存在;16 节建接口时缺它是因为当时无执行语义。
- **D5 循环依赖解法**:core 不能依赖 provider/storage(两者已依赖 core)。故 core 定义两个端口接口——`LlmGateway`(chat(sessionId, Profile, Prompt),由 provider 的 `ProviderService` 实现,类名不动)与 `ToolInvocationAudit`(record(...),由 storage 的 JPA 实现承担);`ReActLoop`/`ToolExecutor` 依赖端口。**新增 2 个接口概念,停点确认。**
- **D6 `tool_invocations` 补列**:现有列为 status/result/error,课件明确要求 success/error_message 且与 llm_calls 同口径——补两列、保留旧列(同 16 节 llm_calls 处理)。
- **D7 历史截断口径**:「最近 N 轮」按 messages 条数截断(保留末尾 N 条),N=maxHistoryTurns 默认 20;轮↔条换算的精确语义 18 节 Session 完整化时再收紧,本节制式写在代码注释里。
- **D8 无 `ToolRegistry` 类**:20 节交付物不抢跑;本节 `ToolExecutor`/`PromptBuilder` 以 `Map<String, OryxTool>`(构造注入)作为「工具表」。
- **D9 Sandbox 调用位**:`ToolExecutor.execute` 执行前一行留 `// Sandbox.enforce(...) —— 24 节接线` 注释标记,不建空实现类。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md / research.md / data-model.md / quickstart.md
├── contracts/            # 内部 API 契约(签名逐字)
└── tasks.md
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/com/oryxos/core/
├── session/Session.java              # D1 内存版
├── session/SessionManager.java       # D2 接口
├── session/InMemorySessionManager.java
├── react/ReActLoop.java              # 主角:run(Session, String, Profile)
├── react/PromptBuilder.java          # 四段拼装 + 截断 + 日期时间
├── react/ToolExecutor.java           # 唯一执行点 + 审计 + Sandbox 调用位
├── react/AgentService.java           # process(Session, String) 编排
├── react/ProfileContext.java         # ThreadLocal set/current/clear
├── react/LlmGateway.java             # D5 端口(ProviderService 实现)
├── react/ToolInvocationAudit.java    # D5 端口(storage 实现)
├── context/ContextLoader.java        # Bootstrap/Skill 读取,无缓存
└── tool/OryxTool.java                # D4 改造:加 execute
    tool/ToolResult.java              # D3

oryxos-storage/src/main/java/com/oryxos/storage/audit/
├── ToolInvocation.java / ToolInvocationRepository.java
└── JpaToolInvocationAudit.java       # D5 core 接口的 JPA 实现

oryxos-provider/src/main/java/com/oryxos/provider/ProviderService.java  # implements LlmGateway(改 implements,签名不动)
oryxos-storage/src/main/resources/db/schema.sql                        # D6 补列

测试:core 5 个(ReActLoopTest/PromptBuilderTest/ToolExecutorTest/AgentServiceTest/ContextLoaderTest)
```

**Structure Decision**: 按落位表;依赖方向保持 core 无下游(core ← provider/storage/tool 等),循环依赖用 D5 端口接口化解。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 新增 2 个端口接口(LlmGateway/ToolInvocationAudit) | Maven 单向依赖下 core 无法引用 provider/storage 具体类 | 把 ProviderService/审计下沉 core 违反 16 节已定落位;直接改依赖方向会造成循环 |
