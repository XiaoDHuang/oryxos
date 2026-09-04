# Phase 1 Data Model: Sandbox 白名单实现（第 24 节）

本 feature 不涉及数据库实体与状态转移；以下是新增类型与其字段契约。既有五件（`Sandbox`/`SandboxAction`/`ActionType`/`SandboxViolationException`/`HttpWhitelistSandbox`）不在此重复定义，本节不改动。

## FileSandboxProperties（新增，public record）

- 注解：`@ConfigurationProperties("file")`
- 字段：`List<String> allowedPaths` ← 绑定 `file.allowed_paths`（宽松绑定，既有键）
- 语义：允许的文件路径根列表；`null`/空 = 文件动作全拒绝

## ShellSandboxProperties（新增，public record）

- 注解：`@ConfigurationProperties("shell")`
- 字段：`List<String> allowedCommands` ← 绑定 `shell.allowed_commands`（既有键）
- 语义：允许的命令首 token 集合；`null`/空 = Shell 动作全拒绝

## HttpSandboxProperties（新增，public record）

- 注解：`@ConfigurationProperties("http")`
- 字段：`List<String> allowedDomains` ← 绑定 `http.allowed_domains`（既有键）
- 语义：允许的精确域名/IP 列表（规范化规则由 `HttpWhitelistSandbox` 既有实现承载）；`null`/空 = HTTP 动作全拒绝

## WhitelistSandbox（新增，public final class，实现既有 `Sandbox`）

- 构造器入参：上述三个 Properties record
- 内部状态（构造期固化、不可变）：
  - `List<Path> allowedRoots`：`allowedPaths` 逐项 `Path.of → normalize → toAbsolutePath`；非法路径项抛 `IllegalArgumentException`（启动失败）
  - `Set<String> allowedCommands`：`Set.copyOf`；`null` 列表按空集处理
  - `HttpWhitelistSandbox httpDelegate`：由 `allowedDomains`（`null` 按空列表）构造
- 行为路由（`enforce(SandboxAction)`）：
  - `FILE_ACCESS` → `checkFilePath`：目标 `Path.of → normalize → toAbsolutePath`，任一 `allowedRoots` 的 `startsWith` 命中则放行，否则抛 `SandboxViolationException("路径不在白名单内: " + 目标)`
  - `SHELL_EXEC` → `checkShellCommand`：`trim().split("\\s+")[0]` 首 token 在集合内则放行，否则抛 `SandboxViolationException("命令不在白名单内: " + 首token)`
  - `HTTP_REQUEST` → 委托 `httpDelegate.enforce(action)`（007 既有严格语义，异常类型同为 `SandboxViolationException`）
- 三个校验方法均 `private`：对外只暴露 `enforce`，接口不被本档实现带偏

## ToolConfiguration（改造，既有 package-private AutoConfiguration）

- 新增 `@EnableConfigurationProperties({FileSandboxProperties, ShellSandboxProperties, HttpSandboxProperties})`
- `sandbox(...)` bean 方法签名改为消费三个 Properties，返回 `WhitelistSandbox`；删除现有手写 `Binder` 绑定 `http.allowed-domains` 的代码
- `@ConditionalOnMissingBean(Sandbox.class)` 保留：用户自定义 Sandbox 优先的语义不变

## 关系

- `WhitelistSandbox` ──实现──▶ `Sandbox`（既有接口，签名不变）
- `WhitelistSandbox` ──组合委托──▶ `HttpWhitelistSandbox`（007 既有，不继承、不修改）
- `FileTools`/`ShellTools`/`HttpTools`/`NotifyTools`/`McpClientService` ──仅依赖──▶ `Sandbox` 接口（调用点零改动）
- `MemoryOutboundConfiguration`（boot，007）──仅依赖──▶ `Sandbox` 接口（接线零改动）
