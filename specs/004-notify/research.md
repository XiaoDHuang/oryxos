# Phase 0 Research: Notify 主动通知出口

## R1 分批交付边界

- **Decision**: 本节立即实现 `NotifyChannelAdapter`、`NotifyTarget`、`WebhookNotifyAdapter` 和
  `WebhookNotifyAdapterTest`；不创建 `NotifyTools` 占位类。
- **Rationale**: 课件明确 `NotifyTools` 的完整接线依赖第 20 节 `@Tool` Schema/ToolRegistry 适配
  和第 24 节 Sandbox；既有 `OryxTool`/`ToolResult` 直接复用。提前实现会制造绕过安全校验或
  重复返工的半成品。
- **Alternatives considered**: 先写空壳 NotifyTools（弃：不可验收且可能被误注册）；本节提前实现
  Sandbox/Registry（弃：抢跑后续交付物）。

## R2 同步 HTTP 客户端与构造注入

- **Decision**: 使用 Spring Web `RestClient` 同步发送；`WebhookNotifyAdapter` 构造器注入
  `RestClient.Builder` 并调用 `build()`。
- **Rationale**: 符合同步阻塞宪法；本地 `javap` 证实 Spring Boot 3.5.16 的
  `RestClientAutoConfiguration` 只提供 Builder Bean。用户已确认该课件示例偏差。
- **Alternatives considered**: 注入 RestClient（弃：容器无默认 Bean）；新增配置类提供 Bean
  （弃：新增课件清单外 public 概念）；WebClient（弃：违反非响应式约束）。

## R3 请求与错误语义

- **Decision**: URL 取自 `NotifyTarget.config["url"]`，空白时在网络调用前抛参数错误；发送
  `application/json` 的 `{"content": content}`；使用 `retrieve().toBodilessEntity()`。
- **Rationale**: 逐字贴合课件核心 payload；RestClient 默认对 4xx/5xx 抛异常，满足 fail-loud。
- **Alternatives considered**: 吞掉异常返回 false（弃：违反验收）；统一平台专用 payload
  （弃：核心阶段边界外）。

## R4 中立目标模型

- **Decision**: `NotifyTarget` 保持 `channelType + Map<String,String> config` 两字段，并防御性复制
  config；渠道专属校验由具体 Adapter 完成。
- **Rationale**: 接口不出现 webhook/企业微信/飞书等实现词，新增 Adapter 不改调用方。
- **Alternatives considered**: 目标直接包含 url 字段（弃：把 webhook 泄漏进通用契约）；为每个平台
  建 Target 子类（弃：核心阶段过度设计）。

## R5 本地测试依赖

- **Decision**: 使用 MockWebServer 4.12.0；根 POM 显式锁版本，`oryxos-tool` test scope 引入。
- **Rationale**: 课件 harness 指定 MockWebServer；本地 jar 和 `enqueue`/`takeRequest`/
  `setResponseCode` API 已通过 `javap` 核实。Boot BOM 未管理该 artifact。
- **Alternatives considered**: 真实 webhook（弃：依赖外网/凭证且不可重复）；自写 HTTP server
  （弃：重复造测试基础设施）。

## R6 Profile 配置复用

- **Decision**: 不修改 Profile 公共契约。
- **Rationale**: `Profile.notifyChannels`、`notify_channels` 映射及 URL 占位解析测试已由第 16 节
  交付并存在。
- **Alternatives considered**: 新增独立配置类型或配置键（弃：重复公共概念并触发软门禁）。
