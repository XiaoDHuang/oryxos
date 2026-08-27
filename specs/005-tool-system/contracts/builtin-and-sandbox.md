# Contract: 七个内置工具与安全前向接口

## Sandbox

```java
void enforce(SandboxAction action);
SandboxAction(ActionType type, String target);
```

ActionType 仅 FILE_ACCESS / SHELL_EXEC / HTTP_REQUEST；拒绝抛 SandboxViolationException。
类型/target 必填且 target 非空白。技术方案三值为准，第24节四值示例由 DR-002 后续同步。

生产无真实白名单时默认全部拒绝。PermissiveSandbox 只位于 src/test/java，无生产绕过开关。
先做纯内存参数/目标校验可以，但 Files.exists/read/write/list、ProcessBuilder.start、网络发送等
实际 IO 必须在 enforce 之后。拒绝消息不回显凭证或完整带查询参数 URL。

## 内置工具输入 / 输出

工具名为对模型发布的稳定名字；下表字段构成 @Tool 方法参数 Schema，名称不能随意更换。
除 notify.channel 外均必填；返回通过 AnnotatedToolAdapter 统一成 ToolResult。

| 名称 | 输入字段 | 正常输出 / 行为 | 安全动作 |
|---|---|---|---|
| read_file | path: string | UTF-8 文件文本 | FILE_ACCESS |
| write_file | path: string, content: string | 创建或整体覆盖文件，返回成功说明；不隐式创建父目录 | FILE_ACCESS |
| list_dir | path: string | 排序后的直接子项名称 JSON 数组，不递归 | FILE_ACCESS |
| shell | command: string | bash 命令合并 stdout/stderr 的 UTF-8 文本 | SHELL_EXEC |
| http_get | url: string | 2xx 响应体文本 | HTTP_REQUEST |
| http_post | url: string, body: string | body 为合法 JSON 文本，按 application/json 发送，返回2xx响应体 | HTTP_REQUEST |
| notify | content: string, channel: string（可选） | 向唯一已配置目标发一次通知，返回成功说明 | HTTP_REQUEST |

path 非空，相对路径以进程工作目录解析并规范化为绝对路径，不偷偷改到 .oryxos；真实路径/符号
链接白名单判断归第24节。write_file.content 可为空字符串，允许明确清空文件。
不存在路径、错误类型、权限和编码错误明确失败。注解描述和参数说明使用中文。

## Shell 生命周期

使用 bash -c + 原始 command 参数，不在 Java 中用空格重新分词执行；command 不能空白。
Sandbox 必须看到将实际执行的完整命令。命令模式白名单与组合命令限制由第24节完成，当前生产拒绝。

单次上限30秒。合并输出流由辅助虚拟线程及时排空，主调用同步等待，不创建固定线程池或并行工具。
捕获上限1MiB，超限明确失败并停止进程，不无限缓冲；退出码非0报告退出码及有界失败输出。
超时/中断销毁进程与已发现的子进程，必要时强制终止，关闭管道并结束读取线程；清理结果须测试，
不能只返回“超时”而遗留后台命令。Windows 无 bash 明确失败，不切换执行语言。
Shell 副作用无法推断，失败默认 retryable=false。

## HTTP 与通知传输

同步 RestClient，连接5秒、读取30秒；只允许 http/https，不接受 URL user-info。
自动重定向关闭，3xx 明确失败，不跟随跳转绕过域名检查；本节不引入重定向策略配置。
响应以流限量读取，超过1MiB失败并关闭资源，不能先完整读取再截断。

- http_get 网络/读取瞬态故障或5xx可标 retryable=true；4xx（含429）、3xx、无效URL、超限不重试。
- http_post/notify 可能已经产生副作用，所有不确定失败默认 retryable=false；不依据5xx盲目重放。
- 状态错误给出安全的状态码诊断，不在日志/错误中回显凭证、完整URL或不受控服务端异常正文。
- WebhookNotifyAdapter 仍保留 RestClient.Builder 构造器、send(NotifyTarget,String)、
  POST application/json、{"content":"..."} 这四项第19节契约；只收紧客户端安全/时限设置。

## Notify 目标解析

从静态 ProfileContext.current().notifyChannels() 读取，不造可注入 ProfileContext。
缺 Profile/空配置/type/url 非法/未解析环境占位符在发送前失败。
channel 缺省或空白取第一项；有值时按 type 精确匹配，找不到或匹配多项都失败。
只接受本节已交付 webhook Adapter，不新增 target name/id、不向多个同 type 目标隐式广播。
content 不允许空白。参数不接受模型指定 webhook URL，也不把配置 URL 填回工具结果。

固定顺序：解析唯一 NotifyTarget → enforce(HTTP_REQUEST,url) → adapter.send(target,content)。
失败仍由 ToolExecutor 统一审计。第19节默认首目标、未配置报错和先 enforce 后 send 的 harness
在本节用 mock 落地；多同 type 的歧义测试不能用“默认首项”掩盖显式选择的歧义。

## 必须保留的验收守点

- 参数化遍历 Registry 中每个工具，name/description/inputSchema 三项 assertNotNull 保留，并加非空白。
- File/Shell/HTTP 正常路径和拒绝路径同时覆盖；拒绝后读写/进程启动/请求次数为零。
- HTTP 正常用本地假服务；越界 execute 抛 SandboxViolationException（RuntimeException 子类），
  保留课件 assertThrows；经 ToolExecutor 则返回失败并写一条失败审计。
- Shell30秒以可注入包内时钟/等待接点验证精确配置，并有短超时真实子进程回收测试；不通过
  缩小生产阈值或删断言让测试变快。
- 默认生产拒绝与测试显式放行分别测试，确认 PermissiveSandbox 不进入生产 JAR。
