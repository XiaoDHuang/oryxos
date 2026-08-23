# Implementation Plan: Agent Provider(大模型统一对接前台)

**Branch**: `016-lesson16-provider` | **Date**: 2026-08-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-agent-provider/spec.md`

## Summary

交付 OryxOS 能力一「对接 LLM」:Profile 三件套(Profile/ProfileLoader/ProfileRegistry,oryxos-core)+ ProviderService 薄抽象与工具 schema 适配器(oryxos-provider)+ LlmCall 审计实体与 Repository(oryxos-storage)。核心机制:provider name → ChatModel 显式映射表、Spring AI 自动工具执行显式关闭(`internalToolExecutionEnabled(false)`)、调用成败均落 `llm_calls` 审计表。

## Technical Context

**Language/Version**: Java 21(Maven release=21)
**Primary Dependencies**: Spring Boot 3.5.16、Spring AI BOM 1.1.8(`spring-ai-starter-model-openai`,已核实 BOM 管理与可下载)、SnakeYAML 2.4(父 pom 已锁定)、Spring Data JPA + SQLite(oryxos-storage 已就位)
**Storage**: SQLite,手工脚本 `oryxos-storage/src/main/resources/db/schema.sql`(实施期从 oryxos-boot 迁入,classpath 位置 `db/schema.sql` 不变,boot 运行不受影响;storage 测试由此可用真实脚本建表);本节为 `llm_calls` 补 `success` / `error_message` 两列(课件硬性要求)
**Testing**: JUnit 5 + Mockito(单测默认跑);`ProviderSmokeIT` 打 `@Tag("integration")` 默认跳过
**Target Platform**: Linux/Windows 服务器,单体式 fat JAR
**Project Type**: 多模块 Maven 单体的功能切片
**Performance Goals**: 无本节专属指标(全局:内部转发开销 <50ms,后续节验证)
**Constraints**: 凭证只走 `${ENV_VAR}` 占位;无 fallback/熔断/hedge racing;审计写入失败不阻断调用主流程(记错误日志)
**Scale/Scope**: 3 个模块新增代码,5 个测试类(4 单测 + 1 冒烟)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 当前是未填充模板,本节以 `AGENTS.md`「七个关键技术决策」为门禁基准核查:

| 决策 | 本节适用性 | 判定 |
|---|---|---|
| 1. 自实现 ReAct loop | 本节不涉(17 节) | N/A |
| 2. Spring AI 只用一半(禁自动 tool 执行) | 核心:`internalToolExecutionEnabled(false)` + 回归测试钉死 | ✅ 设计内建 |
| 3. 同步阻塞 + 虚拟线程 | ChatModel.call 同步调用,无 Reactor/CompletableFuture | ✅ |
| 4. Tool 注册 @Tool + OryxTool 抽象 | 本节只交付最小 `OryxTool` 接口(见设计决策 D2),注册体系在 20 节 | ✅ 不抢跑 |
| 5. Spring MVC | 本节无 HTTP 端点 | N/A |
| 6. Sandbox 白名单(禁 SecurityManager) | 本节不涉外 IO;`ProviderService` 无文件/命令/网络直连(LLM 调用走 Spring AI) | N/A |
| 7. SQLite + JPA,审计表 day one 落库 | `llm_calls` 手工脚本补列 + 实体/Repository | ✅ |

无违规,无需 Complexity Tracking。

## 关键设计决策(Phase 0 research 结论,详见 research.md)

- **D1 Provider 构造**:全局层 `oryxos.providers`(name/api-key/base-url)→ 启动时逐条手工 `OpenAiChatModel.builder()` 建模型,put 进 `Map<String, ChatModel>`;**不靠容器类型扫描**。凭证缺失(clarify Q1 结论)→ 建该客户端时报「供应商 X 凭证缺失(期望环境变量 Y)」,不阻断其余供应商。
- **D2 最小 `OryxTool` 接口落 oryxos-core**(getName/getDescription/getInputSchema):课件 16 节叙事与 harness 均引用,20 节再补执行语义。**超出交付物清单的对外概念——已于 tasks 停点获用户确认(2026-08-16)。**
- **D3 `Prompt` 为 OryxOS 自有轻量类型**(messages + availableTools,`getAvailableTools()`),保真课件签名 `chat(sessionId, Profile, Prompt)`;返回 Spring AI `ChatResponse`(即课件里的 "Response")。**同 D2,已获确认。**
- **D4 schema.sql 改造**:`llm_calls` 增 `success`(INTEGER 0/1)与 `error_message`(TEXT)两列,保留既有 `status` 列不动(对齐课件,最小侵入)。
- **D5 application.yaml 改造**:删除 `spring.ai.openai` 单供应商直配,改为 `oryxos.providers` 列表(deepseek/kimi 示例,`${ENV}` 占位)。

## Project Structure

### Documentation (this feature)

```text
specs/001-agent-provider/
├── plan.md              # 本文件
├── research.md          # Phase 0:依赖与 API 核实记录
├── data-model.md        # Phase 1:Profile / LlmCall 模型
├── contracts/           # Phase 1:ProviderService API 与配置契约
├── quickstart.md        # Phase 1:验证指南
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/com/oryxos/core/
├── profile/Profile.java            # 记录类,全字段(含 notify_channels/schedules)
├── profile/ProfileLoader.java      # 扫 .oryxos/profiles/*.yaml,SnakeYAML 解析+校验
├── profile/ProfileRegistry.java    # Map<String,Profile> 按名索引
├── prompt/Prompt.java              # D3:messages + availableTools
└── tool/OryxTool.java              # D2:最小抽象(20 节补执行语义)

oryxos-provider/src/main/java/com/oryxos/provider/
├── ProviderService.java            # chat(sessionId, Profile, Prompt)
├── ProviderConfiguration.java      # oryxos.providers → Map<String,ChatModel>
├── ProviderProperties.java         # @ConfigurationProperties 全局层
├── ProviderNotFoundException.java  # 未知名/凭证缺失报错
└── ToolSchemaAdapter.java          # OryxTool → Spring AI ToolDefinition(只翻译)

oryxos-storage/src/main/java/com/oryxos/storage/
├── audit/LlmCall.java              # JPA 实体(含 success/error_message)
└── audit/LlmCallRepository.java    # Spring Data JPA

oryxos-storage/src/main/resources/
└── db/schema.sql                   # llm_calls 补 success/error_message(实施期从 boot 迁入)

oryxos-boot/src/main/resources/
└── application.yaml                # oryxos.providers 全局层

测试(与上同构,各模块 src/test):
ProfileLoaderTest、ProviderServiceTest、ToolSchemaAdapterTest、LlmCallRepositoryTest、
ProviderSmokeIT(@Tag("integration"))
```

**Structure Decision**: 按 lesson skill 模块落位表 + TechnicalSolution §10,不新增模块、不跨模块反向依赖(core 不依赖 provider/storage;provider 依赖 core+storage 写审计)。

## Complexity Tracking

无(constitution check 无违规)。
