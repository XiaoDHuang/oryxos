# Contract: ProviderService API

> 内部 Java API 契约(本特性无 REST/CLI 对外接口)。类名与方法签名为课件已定字面量,逐字保真。

## ProviderService(oryxos-provider)

```java
public ChatResponse chat(String sessionId, Profile profile, Prompt prompt)
```

- `sessionId`:审计关联键,不能为空串。
- `profile.provider.name` 在映射表中找不到 → 抛 `ProviderNotFoundException`(消息含该供应商名)。
- `prompt.getAvailableTools()` 非空时:经 `ToolSchemaAdapter` 翻译成 `ToolDefinition` 随请求下发,且请求 options 必须 `internalToolExecutionEnabled(false)`。
- 返回 Spring AI `ChatResponse`(课件中的 "Response"),模型返回的工具调用意图原样保留其中,Provider 不执行。
- 成功:落 `llm_calls`(`success=1`,token/耗时齐全);失败(运行时异常):落 `llm_calls`(`success=0` + `error_message`)后原样上抛。审计写入自身失败仅记日志,不改变上述行为。

## ToolSchemaAdapter(oryxos-provider)

```java
List<ToolDefinition> toSpringAiTools(List<OryxTool> tools)
```

- 纯函数:每个 `OryxTool` → 一个 `ToolDefinition`,`name`/`description`/`inputSchema` 三字段一一对齐;产物不含任何执行逻辑。
- 空入参返回空列表,不返回 null。

## ProfileLoader / ProfileRegistry(oryxos-core)

```java
List<Profile> loadAll(Path profilesDir)   // ProfileLoader:坏文件记错误日志并跳过
Optional<Profile> find(String name)       // ProfileRegistry:按名查找
```

- 加载校验(本节唯一一条):`provider.name` 在全局供应商名集合中不存在 → 该 Profile 记错误日志、不注册、不阻断其余。

## 配置契约(application.yaml)

- 新增 `oryxos.providers` 列表(见 data-model.md),替代并删除既有 `spring.ai.openai.*` 直配段。
- 供应商凭证缺失 → 构建该客户端时报错:「供应商 {name} 凭证缺失(期望环境变量 {VAR})」,其余供应商照常注册。
