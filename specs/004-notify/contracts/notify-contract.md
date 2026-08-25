# Contract: Notify 通知出口

## NotifyChannelAdapter

```java
void send(NotifyTarget target, String content);
```

契约：

- 调用方只提供抽象目标和内容，不传平台 SDK 类型。
- 成功时正常返回；失败时抛异常，不返回伪成功。
- 实现不得缓存目标、凭证或内容。
- 新增渠道实现不得修改该方法签名。

## NotifyTarget

```java
NotifyTarget(String channelType, Map<String, String> config)
```

- `channelType` 标识由哪个 Adapter 解释目标。
- `config` 是渠道专属配置的只读快照。
- 通用类型中不得增加 webhook URL、AccessToken 等渠道专属字段。

## WebhookNotifyAdapter

输入约束：

- `target.config["url"]` 必须存在且非空白。

HTTP 契约：

```text
POST <target.config["url"]>
Content-Type: application/json

{"content":"<content>"}
```

错误契约：

- 4xx、5xx、连接错误向上传递。
- 缺少 URL 时在建立连接前失败。
- 不记录完整 URL 或内容。

## Deferred NotifyTools Contract

第 20/24 节依赖就绪后实现：

```text
notify(content, channel = 默认渠道)
resolve target → Sandbox.enforce(HTTP_REQUEST, url) → adapter.send(target, content)
```

必须满足：未配置明确报错；channel 缺省取第一条；`enforce` 严格先于 `send`；执行成败复用
`ToolExecutor` 写 `tool_invocations`。
