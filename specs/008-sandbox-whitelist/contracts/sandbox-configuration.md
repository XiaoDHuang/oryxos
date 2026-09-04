# Contracts: Sandbox 白名单实现（第 24 节）

本 feature 不新增 REST 端点、CLI 命令或 Java 接口签名。唯一的对外契约面是**配置绑定契约**——三个既有配置键的值语义与失败行为，本节将其从手写 `Binder` 迁移到 `@ConfigurationProperties` record 并固化如下。

## 配置契约（`application.yaml`，键均已存在，本节不改名）

| 键 | 类型 | 默认（boot 内置） | 空值语义 | 非法项处理 |
|---|---|---|---|---|
| `file.allowed_paths` | 字符串列表 | `[".oryxos"]` | 文件动作（`read_file`/`write_file`/`list_dir`）全拒绝 | 无法解析为路径的项 → 启动期 `IllegalArgumentException` 失败 |
| `shell.allowed_commands` | 字符串列表 | `["ls","cat","pwd","echo"]` | `shell` 全拒绝 | 无非法形态（任意字符串都是合法 token）；`null` 项 → 启动失败 |
| `http.allowed_domains` | 字符串列表 | `["api.openweathermap.org"]` | `http_get`/`http_post`/`notify` 全拒绝 | 无法规范化的项（含 `*`、空格、非法端口等）→ 整份配置拒绝，启动失败（007 既有行为） |

## 行为契约

- **放行**：目标经标准化后命中对应白名单 → 校验静默通过（无返回值、无日志噪音），真实 IO 继续。
- **拒绝**：抛 `SandboxViolationException`，消息为简体中文且包含被拒绝目标；由 ToolExecutor 既有失败路径落 `tool_invocations`（`success=false`，`error_message` 为拒绝原因）。不新增审计字段、不新增错误码。
- **优先级**：用户自定义 `Sandbox` bean 优先于本默认装配（`@ConditionalOnMissingBean` 语义不变）。
- **空=全拒绝**：此语义必须同时出现在 `application.yaml` 注释与配置说明文档中。

## 兼容性承诺

- `Sandbox.enforce(SandboxAction)` 签名、`ActionType` 三值、四个内置 Tool 的调用点：零变化。
- `MemoryOutboundGuard`（007）经 `Sandbox` 接口校验 Mem0 目标的链路：行为不变（HTTP_REQUEST 精确匹配语义不变）。
