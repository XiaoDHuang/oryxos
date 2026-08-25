# Data Model: Notify 主动通知出口

## NotifyTarget

通知目标是无持久化、不可变的值对象。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `channelType` | String | 非空、非空白 | 渠道实现的中立标识，如核心阶段的 `webhook` |
| `config` | Map<String,String> | 非 null，构造时防御性复制；允许空 Map | 渠道专属配置；必填键由具体 Adapter 校验 |

### Adapter-specific validation

- `WebhookNotifyAdapter` 要求 `config["url"]` 存在且非空白。
- 其他渠道未来可解释不同配置键，不修改 `NotifyTarget`。
- 配置可能包含凭证，不得写日志或拼进模型参数。

## Notification Content

通知内容是调用方提供的 String：

- Adapter 不擅自改写或生成业务文案；空字符串按原值处理。
- webhook payload 固定映射为 `{"content": <原始内容>}`。
- Adapter 不保存内容或目标，不引入状态机。

## Existing Profile Relationship

Profile 已有 `notifyChannels: List<Map<String,Object>>`，对应 YAML `notify_channels`。后续
`NotifyTools` 接线负责把当前 Profile 中选中的条目转换为 `NotifyTarget`：

- 未指定渠道 → 第一条；
- 指定渠道 → 按 `type` 匹配；
- 无配置或找不到 → 明确失败。

该转换不在本节第一批 Adapter 实现范围内。
