# Phase 0 Research: Sandbox 白名单实现（第 24 节）

本节未知项少：技术栈与依赖全部锁定在既有 BOM 内，接口五件已在代码库。以下决策均已对照本地代码核实。

## 决策 1：HTTP_REQUEST 路由委托 007 已验收组件，精确匹配、不引入通配符

- **Decision**: `WhitelistSandbox` 内部持有 `HttpWhitelistSandbox` 实例（由 `http.allowed_domains` 构造），`HTTP_REQUEST` 动作原样委托 `enforce`。
- **Rationale**: 2026-09-04 用户决议（spec FR-011）。`HttpWhitelistSandbox` 是 007 经完整安全核验的交付物（IDN/IPv4/IPv6/端口/userinfo/fragment 严格规范化，精确匹配，配置含 `*` 拒绝整份配置），委托复用使安全语义零降级、007 的 `HttpWhitelistSandboxTest` 与 boot 组合接线（`MemoryOutboundGuard` → `Sandbox` 接口）全部不受影响。
- **Alternatives considered**: (B) 按课件实现 `*.` 通配符（点号边界 `endsWith`）——需改动 007 已验收组件并重新安全核验，被否；(C) 两套 HTTP 校验并存——同一配置键两种语义，被否。

## 决策 2：白名单根同样做 normalize + toAbsolutePath（修正课件示例代码）

- **Decision**: `WhitelistSandbox` 构造器对 `file.allowed_paths` 每项 `Path.of(...).normalize().toAbsolutePath()`；`checkFilePath` 对目标同样处理后 `startsWith` 目录边界比较。
- **Rationale**: 课件示例只对 allowedRoots 做 `normalize()` 而目标做 `toAbsolutePath()`——`application.yaml` 默认配置 `.oryxos` 是相对路径，相对 root 永远无法被绝对目标 `startsWith` 命中，默认配置下文件工具会被全拒。两边同基准绝对化才符合 spec FR-002"标准化与绝对化后按目录边界比较"。`Path.startsWith` 按路径元素比较，`/workspace-evil` 不以 `/workspace` 开头，前缀歧义天然免疫。
- **Alternatives considered**: 配置侧要求用户必须写绝对路径——把地雷留给用户，被否。

## 决策 3：三个 Properties record 的注册方式沿用项目惯例

- **Decision**: `@ConfigurationProperties("file"|"shell"|"http")` record（构造器绑定，组件为 `List<String>`，字段名 `allowedPaths`/`allowedCommands`/`allowedDomains`），在 `ToolConfiguration` 上加 `@EnableConfigurationProperties({FileSandboxProperties.class, ShellSandboxProperties.class, HttpSandboxProperties.class})`。
- **Rationale**: 本地核实 `Mem0Properties`/`MemoryProperties`/`ProviderProperties` 均为 record + `@EnableConfigurationProperties` 挂在 AutoConfiguration 上的模式；Spring Boot 宽松绑定使 `file.allowed_paths`（yaml 下划线）→ `allowedPaths`（camelCase）成立，既有配置键零变化。`oryxos-tool` 已有 `spring-boot-starter`，无新增依赖。
- **Alternatives considered**: 继续用 `ToolConfiguration` 里的手写 `Binder`（现状）——三个键三份手写绑定是重复劳动，且课件交付物点名三个 `@ConfigurationProperties`，被否。

## 决策 4：ActionType 沿用既有三值，课件四值代码块不采用

- **Decision**: `FILE_ACCESS`/`SHELL_EXEC`/`HTTP_REQUEST` 三值不变；`FILE_ACCESS` 同时覆盖读与写（与技术方案 §6.7 及第 20 节实际交付一致）。课件 harness 中 `FILE_READ`/`SHELL_COMMAND` 写法在测试落地时按三值语义等价翻译，课件原文进 `@DisplayName`。
- **Rationale**: 24 节课件文字自述"已定死、本节沿用"，而实际第 20 节定死的是三值（与技术方案 §6.7 `FILE_ACCESS | SHELL_EXEC | HTTP_REQUEST` 一致）；课件 3.1 的四值代码块与事实源冲突。修改已定枚举字面量属软门禁事项且无收益，不改。
- **Alternatives considered**: 改成四值区分读写权限——技术方案未要求、会破坏既有调用点与 `SandboxContractTest`，被否。

## 决策 5：非法配置项启动期失败

- **Decision**: `FileSandboxProperties`/`ShellSandboxProperties` 的消费点（`WhitelistSandbox` 构造器）对 `null` 列表视为空列表（缺省=全拒绝），对非法路径项（`InvalidPathException`）包装为 `IllegalArgumentException` 启动失败；HTTP 侧沿用 `HttpWhitelistSandbox` 既有"坏项拒绝整份配置"。
- **Rationale**: spec US3 场景 2"明确报错而非静默放行或静默丢弃"；fail-fast 在启动期暴露配置错误，好于运行期首调用才炸。
- **Alternatives considered**: 跳过坏项继续——静默削弱安全策略，被否。

## 决策 6：Shell 首 token 与大小写语义

- **Decision**: `command.trim().split("\\s+")[0]` 取首 token，`Set` 精确比对（大小写敏感）。
- **Rationale**: 与技术方案 §6.7"拆出命令首个 token 比对白名单"及 spec Edge Cases 一致；大小写敏感与文件系统/Shell 语义一致，不做额外归一化引入歧义。
- **Alternatives considered**: 对整行命令做模式匹配——超出发明范围，被否。

## 决策 7：`enforce` 路由用传统 switch 语句

- **Decision**: `switch (action.type())` 传统冒号语句形式，不用箭头表达式。
- **Rationale**: 语法禁区句——P3C/ASM 对增强 switch 的 `->` 形态有解析失败史，静态检查是构建门禁；本节只有一个 switch，传统写法零成本。
- **Alternatives considered**: 箭头 switch——门禁风险，被否。

## 决策 8：接线回归的"IO 未发生"断言方式

- **Decision**: 四个 Tool 各以 mock/伪造底层执行器构造：`ShellTools` 注入伪造 `starter`（既有包私有构造器已支持）；`HttpTools` 注入指向不可达地址或 mock Transport 的客户端并断言连接计数为零；`FileTools` 断言目标文件未创建/内容未变（真实临时目录，断言文件系统状态）；`NotifyTools` 用伪造 `NotifyChannelAdapter` 断言 `send` 从未调用。
- **Rationale**: 课件 harness 原文要求"只断言抛了异常不够，得证明危险动作真的没跑"（`verify(executor, never())` 语义）；优先用各 Tool 既有测试的注入缝，不新增生产代码钩子。
- **Alternatives considered**: 只断言异常类型——不满足课件要求，被否。
