# Implementation Plan: 主动通知出口

**Branch**: `019-lesson19-notify` | **Date**: 2026-08-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-notify/spec.md`

## Summary

在 `oryxos-tool` 内先交付通知出口的第一批可独立能力：中立的 `NotifyChannelAdapter`、通用
`NotifyTarget`、同步 `WebhookNotifyAdapter` 及本地假 webhook harness。Profile 的
`notify_channels` 字段与解析测试已由第 16 节交付，本节只复用。`NotifyTools`、目标解析、
`Sandbox.enforce` 顺序断言和统一工具审计明确延期到第 20/24 节依赖就绪后接线，不写占位实现。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.16；`spring-web` 6.2.19（Boot BOM 管理，用于同步
`RestClient`）；MockWebServer 4.12.0（测试依赖，根 POM 显式锁定）；既有 `oryxos-core`

**Storage**: N/A；通知目标来自既有 Profile `notify_channels`，本节不新增表或配置键

**Testing**: JUnit 5 + AssertJ + MockWebServer；`WebhookNotifyAdapterTest` 覆盖 POST 内容、
配置 URL、缺 URL 预检、4xx/5xx/网络异常上抛和 test-local 第二种 Adapter 的契约兼容性。
`NotifyToolsTest` 延期到第 20/24 节

**Target Platform**: 单体 fat JAR；Linux/Windows；核心部署目标为企业私有网络

**Project Type**: Maven 多模块中的单模块功能切片

**Performance Goals**: 单次同步 HTTP 请求；不引入异步、批量、重试或队列

**Constraints**: 凭证走环境变量占位、不落明文；异常不吞；payload 固定为
`{"content":"..."}`；核心阶段不做平台专用签名/认证/payload；避开响应式 API

**Scale/Scope**: 本节立即新增 3 个 main 类型 + 1 个测试类；`NotifyTools` +
`NotifyToolsTest` 作为后续接线项登记

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 |
|---|---|
| I 自实现 ReAct 循环 | ✅ 通知适配器不介入循环，未来只经既有 ToolExecutor 调用 |
| II Spring AI 仅做协议与 Schema | ✅ 第一批不接 Spring AI；延期 Tool 仍由自有执行链控制 |
| III Provider 显式映射 | ✅ 不涉及 Provider |
| IV 配置即 Agent，上下文非 Tool | ✅ 复用 Profile 通知配置；Adapter 不注册为上下文或 Tool |
| V 审计 Day One 落库 | ✅ 第一批 Adapter 非 Tool；NotifyTools 后续必须复用 ToolExecutor 审计，不新建旁路 |
| VI 安全与数据边界 | ✅ Adapter 尚不注册为 Agent Tool；20/24 节接线前必须 enforce，URL 不进日志/代码 |
| VII 同步执行+虚拟线程+MVC | ✅ 使用阻塞 RestClient，无 Reactor/CompletableFuture/自建线程池 |
| VIII 实例无状态，状态外置 | ✅ Adapter 无状态；目标由调用方提供，不缓存凭证 |

**Post-design re-check**: Phase 1 未新增表、配置键、线程模型或第二套 Tool/Sandbox/审计抽象；
所有 gate 仍通过。

## 关键设计决策

- **D1 包与模块**: `com.oryxos.tool.notify` → `oryxos-tool`。课件的 `io.oryxos` 为示例；
  代码库统一使用既有 `com.oryxos` 包根。
- **D2 构造依赖**: `WebhookNotifyAdapter` 注入 Boot 自动提供的 `RestClient.Builder` 并构建
  `RestClient`。本地 `javap` 已核实 Boot 3.5.16 只自动配置 Builder，不自动提供 RestClient Bean；
  该偏差已经用户确认。
- **D3 HTTP 语义**: POST 到 `NotifyTarget.config().get("url")`，JSON body 为
  `Map.of("content", content)`；`retrieve().toBodilessEntity()` 使 4xx/5xx 默认向上抛。
- **D4 目标模型**: `NotifyTarget(String channelType, Map<String,String> config)` 保持渠道中立，
  防御性复制 config；webhook 专属的 URL 校验留在 Webhook Adapter。
- **D5 Harness**: MockWebServer 4.12.0 已在本地依赖存在，但未被 Boot BOM 管理，因此在根 POM
  显式锁版本，并在 `oryxos-tool` 以 test scope 引入。
- **D6 分批交付**: 本节不创建不完整 `NotifyTools`。第 20 节复用既有 `OryxTool`/`ToolResult`,
  补 `@Tool` Schema/ToolRegistry 适配与 NotifyTools 接入；第 24 节补 Sandbox 后，再原样落地
  `NotifyToolsTest` 的未配置、默认首目标、enforce-before-send 三项。
- **D7 Profile 配置**: `Profile.notifyChannels`、snake_case 加载与字段测试已存在，无需重复改造。

## Project Structure

### Documentation (this feature)

```text
specs/004-notify/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── notify-contract.md
├── checklists/
│   └── requirements.md
└── tasks.md                 # speckit-tasks 阶段生成
```

### Source Code (repository root)

```text
pom.xml                                      # 锁定 MockWebServer 测试版本
oryxos-tool/pom.xml                          # spring-web + MockWebServer(test)
oryxos-tool/src/main/java/com/oryxos/tool/notify/
├── NotifyChannelAdapter.java
├── NotifyTarget.java
└── WebhookNotifyAdapter.java
oryxos-tool/src/test/java/com/oryxos/tool/notify/
└── WebhookNotifyAdapterTest.java

# 延期到 20/24 节，不在本 feature 创建
oryxos-tool/src/main/java/com/oryxos/tool/builtin/NotifyTools.java
oryxos-tool/src/test/java/com/oryxos/tool/builtin/NotifyToolsTest.java
```

**Structure Decision**: 所有 Notify 代码归既有 `oryxos-tool`，不新增模块；接口、目标与 webhook
实现同包，未来平台专用 Adapter 只新增实现类。延期 Tool 归 builtin 包。

## Delivery Phasing

1. **本节立即完成**: 依赖锁定、Adapter/Target/Webhook、`WebhookNotifyAdapterTest`、模块门禁。
2. **第 20 节接线准备**: NotifyTools 适配 `OryxTool`/ToolResult/ToolRegistry，仍不得绕过 Sandbox。
3. **第 24 节最终接线**: 解析 Profile 目标、`Sandbox.enforce` 先于 send、统一审计，全量跑
   `NotifyToolsTest`。

## Complexity Tracking

| Violation/Deviation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 构造器注入 `RestClient.Builder`，不同于课件示例的 `RestClient` | Boot 3.5.16 只自动配置 Builder | 新增 RestClient 配置类超出本节交付物；静态 `RestClient.create()` 降低可测试性与可配置性 |
