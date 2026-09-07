# Implementation Plan: Web Service 与第一版管理平台（010）

**Branch**: `026-lesson26-web-service` | **Date**: 2026-09-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-web-service-admin/spec.md`

## Summary

第 27 节在此 feature 的稳定提交上串联，分支 `027-lesson27-human-trigger`；范围与用户决议见 `lesson27-preflight.md` 和 spec 的 L27-001–007。新增的四类测试位于 provider（MockChatModelTest）与 boot（MockProviderFlowTest/MockAgentE2ETest/HumanTriggerFlowIT），公有 MockChatModel 位于 provider。无新 Maven 依赖/模块/表/端点，Spring AI 调用 API 已由本地 1.1.8 jar 核实；工作区与 Bootstrap 改动落现有 core/memory/tool/cli/boot 配置，公开 Memory 与 SessionManager 方法签名不变。

真实常驻装配检查发现 Boot 未提供 ThreadPoolTaskScheduler，T039 在 CoreEngineConfiguration 增加包私有、条件启用、用户 Bean 优先的 Spring 托管工厂，保留 AgentScheduler 已定构造与执行方式。UI 在现有会话页增加查询参数选择和只读详情，使用已有 GET /sessions/{id}；官网实际设计 token 为准，不新增管理写操作。

测试顺序：MockChatModel 红绿 → 真实组件手工对账 → 随机 HTTP 端口/临时 SQLite 整机验证 → integration 真模型/天气 → 前端构建与浏览器 → 全量 verify/一致性复审。详细证据记录在 lesson27-acceptance.md；无 key 测试不跳门禁，真模型结果不混入默认测试计数。

把运行时内核包装成 `/api/v1` 下 11 个 REST 端点（核心 10 + 用户批准的只读会话列表），六个薄 Controller 共享 CLI 同一引擎入口（`AgentService.process`）；异常单出口复用既有 `ApiErrorResponse`/`ErrorCode`/`OryxException` 地基，`GlobalExceptionHandler` 只加三个映射方法；60 秒超时用 MVC Callable + `spring.mvc.async.request-timeout`；`SessionManager` 端口按用户批准做纯加法扩展（archive + 分页列表 + 会话状态视图）；管理平台为 Vue 3 + Vite 只读 SPA，产物提交进 `static/admin/` 由 Spring 托管并配 SPA 回落；视觉与工程约定固化成 `.agents/skills/oryxos-admin-ui/SKILL.md`（课件 `.claude/skills` 路径经既有软链达成）。

## Technical Context

**Language/Version**: Java 21（maven.compiler.release=21）

**Primary Dependencies**: Spring Boot 3.5.16（spring-boot-starter-web/validation/test 已在 oryxos-web）、springdoc-openapi 2.8.17（已在 BOM 与 web pom）、Spring AI 1.1.8（仅库 jar，无 autoconfigure——research R1）、前端 Vue 3 + Vite（与 website/ 同栈）

**Storage**: SQLite（既有 sessions 表，含 status/archived_at 列，无表结构变更）；长期记忆经 `LongTermMemoryStore.load()` 只读视图

**Testing**: JUnit 5 + Mockito + AssertJ + spring-boot-starter-test（@WebMvcTest 切片、@SpringBootTest 冒烟）；surefire 默认排除 `integration` 组

**Target Platform**: 企业私有部署单体 fat JAR（Linux 主流发行版）

**Project Type**: web-service（REST API + 静态 SPA 托管）

**Performance Goals**: Session 创建 P99 < 200ms、内部转发开销 < 50ms（AGENTS.md 红线，不达标不阻塞发布）、200 并发 invoke 稳定（人工压测）

**Constraints**: 单条消息 ≤32KB；历史返回 ≤100 条；Agent 调用 60s 超时 → 504；500 不泄漏内部细节；同步阻塞 + 虚拟线程（宪法 VII）；无认证/CORS 全开放（核心阶段）

**Scale/Scope**: 11 个 REST 端点、6 个 Controller、1 个只读管理台（5 页）、1 个风格 skill

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 证据 |
|---|---|---|
| I 自实现 ReAct | PASS | 不改引擎；Web 只调既有 `AgentService.process` |
| II Spring AI 只用一半 | PASS | 无 eager autoconfigure 可排（R1：classpath 无对应构件）；不引入自动 tool 执行 |
| III Provider 显式映射 | PASS | /info 只读既有显式注册表，不引入新路由 |
| IV 配置即 Agent | PASS | 不新建任何 Agent Java 子类；/agents 增删改留白 |
| V 审计 Day One | PASS | 走既有引擎路径，llm_calls/tool_invocations 照常落库；invoke 一次性会话真实落库（R4） |
| VI 安全与数据边界 | PASS | 不新增外发；凭证环境变量不变；Sandbox 链路不动；web 不引入 memory→tool 依赖 |
| VII 同步+虚拟线程+Spring MVC | PASS | Callable 体内同步阻塞；无 Reactor/WebFlux/CompletableFuture/自建线程池/SSE |
| VIII 状态外置 | PASS | 无表结构变更；归档走既有 status/archived_at 列；SQLite 手工脚本原则不触碰 |
| 模块边界（9 模块） | PASS | 不新增/合并模块；web→{core,provider,memory,tool} 依赖边全部既有；SessionManager 纯加法经用户批准 |
| Memory 后端兼容 | PASS | GET /memory 经 `LongTermMemoryStore.load()`，三后端统一视图，不发明第二套读取 |

**结论**: 无违规，无 Complexity Tracking 条目。

## Project Structure

### Documentation (this feature)

```text
specs/010-web-service-admin/
├── plan.md              # 本文件
├── research.md          # R1-R9 决策与核实证据
├── data-model.md        # 视图/DTO/端口扩展，无新表
├── quickstart.md        # 自动化 + 人工验证步骤
├── contracts/
│   └── api-v1.md        # 11 端点契约 + 错误语义 + 托管约定
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/com/oryxos/core/session/
├── SessionManager.java        # 加法：archive + listSessions
├── Session.java               # 加法：archived() 标记
├── SessionSummary.java        # 新增 record
└── SessionPage.java           # 新增 record

oryxos-storage/src/main/java/com/oryxos/storage/session/
├── JpaSessionManager.java     # 实现两个新端口方法；toRuntime 填充 archived
└── SessionEntity.java         # 加法：markArchived

oryxos-web/src/main/java/com/oryxos/web/
├── api/
│   ├── GlobalExceptionHandler.java        # 扩展：+AsyncRequestTimeoutException→504、+ProviderNotFoundException→503、+IllegalArgumentException→400
│   ├── SessionApiController.java          # 5 端点（含 GET / 列表）
│   ├── AgentApiController.java            # POST /agents/{name}/invoke
│   ├── ProfileApiController.java          # GET /profiles
│   ├── MemoryApiController.java           # GET /memory
│   ├── ToolApiController.java             # GET /tools
│   └── SystemApiController.java           # GET /health、GET /info
└── config/
    └── AdminSpaWebConfiguration.java      # /admin/** 静态托管 + SPA 回落 index.html

oryxos-web/src/main/frontend/              # Vue 3 + Vite 工程（五页只读管理台）
oryxos-web/src/main/resources/static/admin/   # npm build 产物（提交进 git）

oryxos-web/src/test/java/com/oryxos/web/api/
├── SessionApiControllerTest.java          # @WebMvcTest 切片 harness
└── GlobalExceptionHandlerTest.java        # 扩展既有：新增映射 + 500 不泄漏回归

oryxos-boot/src/test/java/com/oryxos/boot/
└── WebSmokeIT.java                        # @SpringBootTest 真实上下文冒烟（默认跑）

oryxos-boot/src/main/resources/application.yaml   # +spring.mvc.async.request-timeout: 60000

.agents/skills/oryxos-admin-ui/SKILL.md    # 风格 skill（.claude/skills 经软链达成）
```

**Structure Decision**: 全部落在既有 9 模块内；Controller 与异常处理归 oryxos-web，端口扩展归 core/storage，系统级冒烟归 oryxos-boot（既有 IT 的家），前端工程与产物归 oryxos-web，风格 skill 归 .agents/skills（单一事实来源）。

## 关键设计决策索引

全部决策与核实证据见 [research.md](research.md)：R1 不做 autoconfigure 排除（classpath 无对应构件）；R2 Callable+async timeout 实现 60s/504；R3 SessionManager 扩展形状；R4 invoke 一次性会话身份与归档收尾；R5 503 载体（ProviderNotFoundException 确定 + 传输层异常类型实现期核实）；R6 前端手动构建产物提交；R7 WebSmokeIT 落 oryxos-boot、静态块建 .oryxos 夹具、不打 integration 标签；R8 不建课件示意的五个新异常类、复用 OryxException/ErrorCode、500 话术沿用"服务器内部错误"；R9 配置仅增一行 request-timeout。

## 课件与现状差异登记（均已裁决或有出处）

1. 课件称 GlobalExceptionHandler 有"既有 IllegalArgumentException→400 映射"——现状没有；本节补齐该映射（加法），契约已注明。
2. 课件 §三第三步示例把 IllegalStateException→503——与同一课件 harness 回归（IllegalStateException 期待 500）矛盾；以 harness 为准，不做该映射（R5）。
3. 课件 harness 500 话术"内部错误"——用户裁决沿用既有"服务器内部错误"，其余断言逐字保真。
4. 课件管理台提示词引用的 GET /api/v1/sessions 不在核心 10 端点——用户批准作为第 11 个只读端点加入。
5. 课件坑四（OpenAiAutoConfiguration eager 装配）——classpath 无该构件，结构性不成立（R1），WebSmokeIT 即回归证据。

## Complexity Tracking

无。
