# Contracts: 定时任务（第三种触发源）

本特性不新增 REST 端点、不新增 CLI 子命令。对外契约两处：Profile 的 `schedules` 配置块、调度启用信号。

## C1：Profile `schedules` 配置块

Profile YAML 新增正式语义的 `schedules` 列表（此前仅透传）：

```yaml
schedules:
  - id: <规则 id，非空白，全实例唯一>
    cron: "<Spring 六段式 cron，含秒，如 0 0 9 * * * = 每天 09:00>"
    zone: "<IANA 时区标识，如 Asia/Shanghai>"
    message: "<到点发给 Agent 的消息，非空白>"
```

行为契约：

- 四要素任一缺失/空白、cron 或 zone 非法、id 重复 → 该条记错误日志并跳过，不影响启动与其他规则。
- cron 按 `zone` 指定时区解释；不依赖服务器系统时区。
- 修改本配置块需重启进程生效；不支持运行时增删。
- 到点触发等价于以 ("scheduler","scheduler",profileName) 身份向统一处理入口发送 `message`。

## C2：调度启用信号 `oryxos.scheduler.enabled`

- 形式：Spring Boot 程序参数 `--oryxos.scheduler.enabled=true`，由 `serve` 与 `gateway` 命令启动运行时传入。
- 缺省 `false`：`chat` 命令及任何不传该参数的启动方式，定时任务注册数为零（用户决议，对齐技术方案 §8.6）。
- 该键仅供 OryxOS 内部命令接线使用，不是给用户手配的开关；文档以 `serve`/`gateway` 语义表述。
