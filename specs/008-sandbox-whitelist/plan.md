# Implementation Plan: Sandbox 白名单实现（第 24 节）

**Branch**: `024-lesson24-sandbox` | **Date**: 2026-09-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-sandbox-whitelist/spec.md`

## Summary

把第 20 节定死、007 部分提前实现的 Sandbox 接口接上核心阶段唯一一档完整实现：在 `com.oryxos.tool.sandbox` 包内新增 `WhitelistSandbox`（按 ActionType 路由文件路径/Shell 命令/HTTP 目标三类白名单校验）与三个 `@ConfigurationProperties` record，并把 `ToolConfiguration` 的默认 Sandbox bean 从"仅 HTTP 白名单、其余拒绝"升级为该实现。HTTP_REQUEST 路由按用户决议（2026-09-04）委托 007 已验收的 `HttpWhitelistSandbox`，精确匹配、不引入通配符。校验失败复用 ToolExecutor 既有失败审计路径，零新增审计代码；接口五件与四个 Tool 调用点一行不改。

## Technical Context

**Language/Version**: Java 21（避开 P3C/ASM 解析不了的 Java 18+ 语法形态，如增强 switch 的 `default ->` 写法——`enforce` 路由用传统 switch 语句或 if 链）

**Primary Dependencies**: Spring Boot 3.x（`@ConfigurationProperties` 构造器绑定 record + `@EnableConfigurationProperties` 注册，项目既有惯例见 `Mem0Properties`/`MemoryProperties`/`ProviderProperties`）；无新增第三方依赖

**Storage**: N/A（无新表；审计继续落既有 `tool_invocations`）

**Testing**: JUnit 5 + AssertJ + Mockito（既有测试栈）；`WhitelistSandboxTest` + 四个 Tool 接线回归（mock 底层执行器断言 IO 未发生）+ `ToolConfigurationTest` 默认装配断言更新；单测默认跑，集成冒烟 `@Tag("integration")`

**Target Platform**: Windows/Linux/macOS 开发机 + Linux 服务器；路径比较走 `java.nio.file.Path`（平台语义正确：目录边界比较、Windows 大小写不敏感由文件系统语义承载）

**Project Type**: 单体运行时内核的内部安全组件（Maven 模块 `oryxos-tool`）

**Performance Goals**: 校验为纯内存比较（路径 normalize + 集合查找），单次数微秒级，无专项指标

**Constraints**: 白名单配置为空 = 该类全拒绝；非法配置项启动期明确失败；不得弱化 007 HTTP 严格规范化语义；不得引入 SecurityManager

**Scale/Scope**: 1 个实现类 + 3 个 Properties record + 1 处装配升级 + 测试套件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. 自实现 ReAct**：不涉及循环控制，无影响。✅
- **II. Spring AI 仅用一半**：不涉及；本节不触碰 Tool 注册与执行权。✅
- **III. Provider 显式映射**：不涉及。✅
- **IV. 配置即 Agent**：无新 Agent 类型、无新上下文资产。✅
- **V. 审计 Day One 落库**：校验失败复用 ToolExecutor 既有失败审计路径写 `tool_invocations`（`success=false`），零新增审计代码、无静默失败。✅
- **VI. 安全与数据边界是地基**：本 feature 正是 VI 的落地——文件路径、Shell 首 token、HTTP 域名分别校验；MUST NOT 用 SecurityManager（不引入）；凭证不入配置（本 feature 无凭证）；HTTP 校验沿用 007 严格规范化，缺安全接线拒绝的语义不变（白名单空=全拒绝）。✅
- **VII. 同步执行与虚拟线程**：不引入 Reactor/CompletableFuture/自建线程池。✅
- **VIII. 状态外置**：无新表、无 SQLite schema 变化、无 `ddl-auto` 依赖。✅
- **9 模块边界**：全部改动在 `oryxos-tool` 模块内（sandbox 包 + ToolConfiguration），无模块新增/改名/职责迁移。✅
- **Memory 后端范围**：不涉及 memory；007 的 boot 组合接线（`MemoryOutboundGuard` 经 `Sandbox` 接口校验 HTTP_REQUEST）仅依赖接口，本升级保持其语义。✅

门禁全部通过，无 Complexity Tracking 项。

## Project Structure

### Documentation (this feature)

```text
specs/008-sandbox-whitelist/
├── plan.md              # 本文件
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/           # Phase 1 输出（配置绑定契约）
└── checklists/          # specify 阶段产出
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/com/oryxos/tool/
├── sandbox/
│   ├── Sandbox.java                    # 既有，不动
│   ├── SandboxAction.java              # 既有，不动
│   ├── ActionType.java                 # 既有三值，不动
│   ├── SandboxViolationException.java  # 既有，不动
│   ├── HttpWhitelistSandbox.java       # 既有（007），不动——被委托复用
│   ├── WhitelistSandbox.java           # 新增：三类路由的实现本体
│   ├── FileSandboxProperties.java      # 新增：@ConfigurationProperties("file")
│   ├── ShellSandboxProperties.java     # 新增：@ConfigurationProperties("shell")
│   └── HttpSandboxProperties.java      # 新增：@ConfigurationProperties("http")
└── ToolConfiguration.java              # 改造：默认 Sandbox bean → WhitelistSandbox，
                                        # 注册三个 Properties，删除手写 Binder 绑定

oryxos-tool/src/test/java/com/oryxos/tool/
├── sandbox/
│   ├── WhitelistSandboxTest.java       # 新增：课件 harness 主体
│   └── （既有 HttpWhitelistSandboxTest/SandboxContractTest/PermissiveSandbox 不动）
├── builtin/
│   ├── FileToolsTest.java              # 追加：白名单外拦截且 IO 未发生
│   ├── ShellToolsTest.java             # 追加：同上
│   ├── HttpToolsTest.java              # 追加：同上
│   └── NotifyToolsTest.java            # 追加：同上
└── ToolConfigurationTest.java          # 更新：默认装配断言 HttpWhitelistSandbox → WhitelistSandbox

oryxos-boot/src/main/resources/application.yaml  # 既有三配置键与默认值不动；注释补"空=全拒绝"语义
docs/ 或 website 配置说明                        # 配置边界文档（空=全拒绝）
```

**Structure Decision**: 全部落在既有 `oryxos-tool` 模块的 `sandbox` 包与装配层；不新增模块、不新增对外 REST/CLI 接口、不新增配置键。

## Complexity Tracking

无——Constitution Check 全绿，无需要论证的例外。
