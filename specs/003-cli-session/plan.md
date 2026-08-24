# Implementation Plan: CLI 命令行入口与会话持久化

**Branch**: `018-lesson18-cli` | **Date**: 2026-08-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-cli-session/spec.md`

## Summary

交付能力五的本地入口与 `sessions` 持久化地基:`OryxOsCli` 挂齐 12 个子命令(轻重分流)、`CliChannel` 薄壳交互、`SessionEntity`+`SessionRepository`+`JpaSessionManager`(三元组幂等、messages_json 整存整取)、引擎 Bean 装配(`CoreEngineConfiguration`)。重命令启动类显式声明 `@EnableJpaRepositories`/`@EntityScan`(课件坑)。

## Technical Context

**Language/Version**: Java 21
**Primary Dependencies**: Picocli(已在 cli 模块,纯 Picocli 不带 Spring starter)、Spring Boot 3.5.16、Spring Data JPA + SQLite、Jackson(messages_json 序列化)、第 16/17 节交付物
**Storage**: `sessions` 表(schema.sql 已有,含 context_state 列,不动表结构);messages_json 整列 JSON
**Testing**: JUnit 5 + Mockito;`SessionManagerTest`(SQLite 临时库+真实脚本)、`SessionRepositoryTest`(序列化回读/重启重查)、`OryxOsCliHelpTest`(12 叶子帮助)、`ChatCommandTest`(未知 Profile 无孤儿会话);真实交互与重命令启动日志保留人工清单
**Target Platform**: 单体 fat JAR,`java -jar` 驱动 CLI
**Project Type**: 多模块 Maven 功能切片
**Performance Goals**: 轻命令秒回(不启动 Spring;fat JAR 冷启动含 JVM 成本,实测约 2s,可接受)
**Constraints**: session_id 只在 SessionManager 内拼接;CLI 无 Agent 逻辑;表结构手工脚本
**Scale/Scope**: cli 12 个叶子操作对应命令类 + 4 个分组父命令、channel-cli 1 类、storage 3 类、core 装配 1 类、boot 启动类改造

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 |
|---|---|
| I 自实现 ReAct 循环 | ✅ 本节只接入既有 `ReActLoop`,不让出循环控制权 |
| II Spring AI 仅做协议与 Schema | ✅ CLI 不启用框架自动 tool 执行或 eager Provider 装配 |
| III Provider 显式映射 | ✅ 复用 `ProviderConfiguration` 的显式 name → ChatModel 注册表 |
| IV 配置即 Agent,上下文非 Tool | ✅ CLI 只按 Profile 名选择 Agent;上下文与工具注册边界不变 |
| V 审计 Day One 落库 | ✅ 复用既有 llm_calls/tool_invocations 审计;本节新增 sessions 持久化 |
| VI 安全与数据边界 | ✅ 无明文密钥、无遥测;外部值进入日志前清洗 |
| VII 同步执行+虚拟线程+MVC | ✅ chat/serve/gateway 同步阻塞,无 Reactor/WebFlux/异步编排 |
| VIII 实例无状态,状态外置 | ✅ Session 从内存占位升级为 SQLite,JPA 不做自动迁移 |

## 关键设计决策(详见 research.md;D1~D4 停点确认)

- **D1 实体命名 `SessionEntity`**(storage):core 已有运行时 `Session`(17 节),同名两类必混淆;课件只要求"sessions 表 JPA 实体",未给类名字面量。
- **D2 `SessionManager`(17 节 core 接口)扩展**:加 `getOrCreate(channel, user, profileName)` 与 `get(sessionId)`——17 节已预告本节完整化,课件明列 SessionManager 为本节交付物。实现侧:`JpaSessionManager`(storage)承担持久化;**删除 17 节的 `InMemorySessionManager`**(不留死代码,其唯一使命是 17 节占位)。
- **D3 id 拼接格式**:`channel + ":" + user + ":" + profileName`,只在 `JpaSessionManager` 内部(H4④);三个分量必须非空且禁止冒号,避免分隔符碰撞。可读、确定、幂等。
- **D4 引擎装配 `CoreEngineConfiguration`**(core,`@Configuration`):产出 ProfileLoader/ProfileRegistry/ContextLoader/PromptBuilder/ReActLoop/ToolExecutor(空工具表,20 节填)/AgentService/SessionManager(JpaSessionManager 在 storage 已是 @Component)等 Bean;重命令经它拉起引擎。属基础设施,非业务对外概念。
- **D5 轻重分流落法**:仅 `chat`/`serve`/`gateway` 为重命令(起 Spring);`init`/`status`/`profile *`/`provider list`/`tool list`/`session list` 全轻——provider list 与 tool list 按课件"要不要跑引擎"标准走文件级实现(扫 profiles/*.yaml 汇总),session list 直连 SQLite JDBC,均秒回。
- **D6 serve/gateway 本节形态**:serve 起 Web 运行时(REST 业务端点 26 节);gateway 起非 Web 守护骨架(日志提示核心阶段仅 CLI 通道)。
- **D7 messages_json 格式**:`[{role, content, toolCalls?}]`;回读按 role 重建 UserMessage/AssistantMessage(builder 带 toolCalls,已核实存在)/ToolResponseMessage;SystemMessage 由 PromptBuilder 每轮现拼、不进 Session,未知角色响亮失败。
- **D8 `OryxOsApplication` 显式加 `@EnableJpaRepositories("com.oryxos.storage")` + `@EntityScan("com.oryxos.storage")`**(课件约定;com.oryxos 包下默认虽能扫到,显式声明防包结构调整后静默翻车)。
- **D9 工作区定位**:命令以当前目录 `.oryxos/` 为准;未初始化给清晰报错("请先 oryxos init"),不抛栈。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli-session/
├── plan.md / research.md / data-model.md / quickstart.md
├── contracts/            # SessionManager 契约 + 命令清单
└── tasks.md
```

### Source Code (repository root)

```text
oryxos-cli/src/main/java/com/oryxos/cli/
├── OryxOsCli.java               # 改造:挂齐 12 子命令
├── InitCommand.java             # 既有(轻)
├── VersionCommand.java          # 既有
├── StatusCommand.java           # 轻:工作区检查
├── ProfileCommand.java          # 轻(父命令,挂 list/show/create/delete)
├── profile 子命令               # ProfileListCommand/ProfileShowCommand/ProfileCreateCommand/ProfileDeleteCommand(轻)
├── ProviderCommand.java + ProviderListCommand.java   # 轻(父+子;扫 profiles YAML 汇总)
├── ToolCommand.java + ToolListCommand.java           # 轻(父+子;读指定 profile 声明的 tools)
├── SessionCommand.java + SessionListCommand.java     # 轻(父+子;JDBC 直查 sessions 表)
├── ChatCommand.java / ServeCommand.java / GatewayCommand.java   # 重:起 Spring
└── SpringRuntime.java           # 重命令共享的启动入口(Class.forName 引 boot 主类,破 Maven 循环)

oryxos-channel-cli/src/main/java/com/oryxos/channel/cli/
└── CliChannel.java              # chat 交互壳(读-转交-打印,/quit)

oryxos-storage/src/main/java/com/oryxos/storage/session/
├── SessionEntity.java           # D1:sessions 表实体
├── SessionRepository.java
└── JpaSessionManager.java       # D2/D3:三元组幂等 + JSON 序列化

oryxos-core/src/main/java/com/oryxos/core/
├── session/SessionManager.java  # D2:加 getOrCreate/get(删 InMemorySessionManager)
└── config/CoreEngineConfiguration.java   # D4 引擎装配

oryxos-boot/src/main/java/com/oryxos/OryxOsApplication.java   # D8 显式扫描声明

测试:oryxos-storage SessionManagerTest + SessionRepositoryTest
```

**Structure Decision**: 按落位表(18 行);core 保持无下游依赖,storage 实现 core 接口,cli/channel-cli 只作入口。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 新增 `CoreEngineConfiguration` 装配类 | 引擎类是纯 POJO(17 节无入口),重命令需要 Spring Bean 装配 | 给每个引擎类加 @Component 会把 core 绑上 Spring 注解,纯 POJO 更易单测 |
