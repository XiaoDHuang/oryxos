# Implementation Plan: 三层 Memory 核心能力

**Branch**: `022-lesson22-memory` | **Date**: 2026-08-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-memory/spec.md`

## Summary

以一个核心层 Memory 端口把 `PromptBuilder` 与具体实现隔开，在 `oryxos-memory` 实现单一文件式长期记忆：同一个 `MEMORY.md` 分核心/归档两区，核心全量注入，归档最近 4000 字注入并仅做关键词检索，不缓存。Agent 通过 `save_memory` / `recall_memory` 主动读写，工具仍走第20节统一注册、授权、执行与审计路径；会话记忆复用现有 Session/SQLite，不新建表。用户已批准端口接口职责从 memory 模块反转到 core，九模块数量不变，事实源和运行指导已同步。

## Technical Context

**Language/Version**: JDK 21

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI 1.1.8 的 `@Tool` / `@ToolParam` 与 Message 类型；既有 OryxOS Core/Memory/Tool/Storage 模块。`dependency:tree` 已确认 `spring-ai-model:1.1.8`，本地 `javap` 已确认 `ToolParam.required()` / `description()` 存在；不新增第三方版本。

**Storage**: 会话历史继续使用既有 SQLite `sessions`；长期记忆仅使用 `.oryxos/memory/MEMORY.md`，无新 schema、表或迁移。

**Testing**: JUnit 5、AssertJ、Mockito、`@TempDir`；单元测试默认执行，必要的 Boot 真实装配/重启恢复场景标 `@Tag("integration")` 并显式运行。

**Target Platform**: Linux 主流发行版与 Windows 开发环境；单 Spring Boot fat JAR、企业内网部署。

**Project Type**: 既有 Maven 9 模块企业级单体运行时。

**Performance Goals**: 长期记忆保持小文件量级，每轮同步读取预计 1–2ms；不得为了性能加入内容缓存。继续满足 100 并发 Session 和内部转发开销目标，不把 LLM 延迟计入。

**Constraints**: 同步阻塞；不使用 Reactor、`CompletableFuture` 或自建线程池；核心区永不截断；归档注入上限 4000 字；写后下一读立即可见；工作区级作用域；错误/审计消息为简体中文；不写 `USER.md`；不引入 SQLite 长期记忆、Mem0、向量、自动提炼、缓存或新配置键。

**Scale/Scope**: 核心阶段一个工作区一份长期记忆文件，几 KB 到几十 KB；单运行实例内并发保存必须无丢失。多租户、每用户/Profile 隔离及多进程共享写属于扩展阶段。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 设计核对 | 结论 |
|---|---|---|
| I 自实现 ReAct | 只给既有 `PromptBuilder` 注入 Memory 端口，不改变 ReAct 循环控制、终止或 Tool 调度权 | PASS |
| II Spring AI 边界 | 仅使用 Message 类型与 `@Tool` Schema；Memory Tool 仍由 `ToolExecutor` 执行，自动 Tool 执行配置不变 | PASS |
| III Provider 显式映射 | 不修改 Provider 配置或映射 | PASS |
| IV 配置即 Agent / Tool 统一 | 不新增 Agent 类或 Profile 字段；Memory Tool 经 `AnnotatedToolAdapter` 包装为 `OryxTool`，仅 Profile 声明后可见 | PASS |
| V 审计 Day One | 两个 Memory Tool 复用 `ToolExecutor` 最终一次 `tool_invocations` 审计；不新增旁路执行 | PASS |
| VI 安全与数据边界 | 只访问固定工作区 Memory 路径，无任意路径参数、外部服务或凭证；`USER.md` 保持只读 | PASS |
| VII 同步执行 | 文件 IO 与工具调用均同步，不引入响应式 API、异步编排或线程池 | PASS |
| VIII 状态外置 | 长期状态只在 `MEMORY.md`，会话状态仍在 SQLite；无进程内唯一状态或长期内容缓存 | PASS |
| 九模块与职责演进 | 模块数不变；用户已显式批准 `MemoryService`/`MemoryScope` 端口归 core、实现归 memory，且 `AGENTS.md`、TechnicalSolution、AiProgrammingGuide、课件已同步 | PASS |

**Pre-design gate**: PASS。没有未解释的宪法冲突。

## Project Structure

### Documentation (this feature)

```text
specs/006-memory/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── memory-contract.md
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/com/oryxos/core/memory/
│   ├── MemoryService.java
│   └── MemoryScope.java
├── src/main/java/com/oryxos/core/react/PromptBuilder.java
├── src/main/java/com/oryxos/core/config/CoreEngineConfiguration.java
└── src/test/java/com/oryxos/core/react/PromptBuilderTest.java

oryxos-memory/
├── pom.xml
├── src/main/java/com/oryxos/memory/
│   ├── MemoryServiceImpl.java
│   ├── LongTermMemory.java
│   ├── MemoryTools.java
│   └── MemoryConfiguration.java              # 包私有自动配置
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── src/test/java/com/oryxos/memory/
    ├── LongTermMemoryTest.java
    ├── MemoryServiceImplTest.java
    └── MemoryToolsTest.java

oryxos-tool/
├── pom.xml                                  # 新增内部模块依赖 oryxos-memory
├── src/main/java/com/oryxos/tool/ToolConfiguration.java
└── src/test/java/com/oryxos/tool/ToolConfigurationTest.java

oryxos-cli/
├── src/main/java/com/oryxos/cli/InitCommand.java
└── src/test/java/com/oryxos/cli/InitCommandTest.java

oryxos-boot/
└── src/test/java/com/oryxos/boot/MemorySystemIntegrationTest.java

.oryxos/memory/MEMORY.md                    # 运行时/人工验收资产，不提交用户本地内容
```

**Structure Decision**: 采用端口反转而非 `core → memory` 依赖。`MemoryService` 与 `MemoryScope` 是引擎所需的跨模块契约，归 `oryxos-core`；`oryxos-memory` 依赖 core 并实现契约。`oryxos-tool` 新增对 memory 的内部依赖，仅为把 `MemoryTools` 明确纳入内置集合并绕开普通 Java 插件的默认拒绝 guard；memory 不依赖 tool，因此无循环。Boot 聚合模块负责最终装配。包私有 `MemoryConfiguration` 只是 Spring 接线，不形成新产品概念。

## Phase 0 Research Decisions

研究结论详见 [research.md](research.md)。关键决定：

1. `MemoryService.buildContext(Session, int)` 返回长期记忆 System Message 与最近会话 Message 列表；不新增 `MemoryContext` 公共类型。
2. `PromptBuilder` 保留既有二参构造器作为无记忆兼容入口，新增三参构造器；生产配置通过 `ObjectProvider<MemoryService>` 注入真实实现。
3. `LongTermMemory` 每次现读，同一规范化路径共用 JVM 锁，并以同目录临时文件原子替换完成“读—规范化—写”，防止同进程多实例并发丢失；核心阶段不承诺多进程共享写。
4. `MemoryTools` 是项目内置工具，不是普通 Java Plugin；`ToolConfiguration` 明确把它加入无额外插件 guard 的内置集合，之后仍统一包装、授权、执行和审计。
5. 初始化文件固定含 `## 核心记忆` / `## 归档记忆`；不存在时创建，已有畸形/歧义分区失败关闭，不覆盖用户内容。
6. 归档截断与检索只作用归档区；核心区完整，未命中返回稳定中文提示。

## Phase 1 Design

- [data-model.md](data-model.md)：定义工作区级 Memory 文档、条目、scope、上下文组合与状态转换；明确无关系型 schema。
- [contracts/memory-contract.md](contracts/memory-contract.md)：冻结核心端口签名、文件格式、Tool 参数/结果、Prompt 顺序和失败语义。
- [quickstart.md](quickstart.md)：给出默认单测、关键 harness、显式 Boot integration、完整门禁和人工真模型验证步骤。

## Post-design Constitution Check

Phase 1 产物未引入新模块、表、配置键、Profile 字段、外部服务或第二套 Memory 抽象。端口职责迁移已获用户批准并同步事实源；`oryxos-tool → oryxos-memory → oryxos-core` 保持单向，Memory Tool 仍只有 `ToolExecutor` 一条执行/审计路径。八项原则与九模块门禁继续全部 PASS。

## Complexity Tracking

无宪法例外。端口职责调整属于宪法允许的已审批模块演进，必要性、替代方案和同步证据已记录，而非豁免。
