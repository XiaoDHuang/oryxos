# Contract: ReAct 循环内部 API

> 内部 Java 契约。课件已定字面量逐字保真;D5 端口接口与 D4 改造点待 tasks 停点确认。

## ReActLoop(oryxos-core)

```java
public String run(Session session, String userMessage, Profile profile)
```

- 入:Session(内存版)+ 用户消息 + Profile;出:最终文本答复。
- 轮数上限 `profile.settings().maxIterations()`;转满返回「达到最大轮数,已停止」语义收尾语。
- 每轮:`PromptBuilder.build` → `LlmGateway.chat(session.id(), profile, prompt)` → 响应累积进 Session → 无 toolCalls 返回文本;有则逐个 `ToolExecutor.execute(session.id(), toolCall)` 并回填。

## PromptBuilder(oryxos-core)

```java
public Prompt build(Session session, Profile profile)
```

四段固定顺序:system(identity prompt + ContextLoader 内容 + 当前日期时间)→ 长期记忆(留接入位,本节恒空)→ 截断历史(末尾 N 条)→ 可用工具(按 `profile.tools()` 从工具表过滤)。

## ToolExecutor(oryxos-core)

```java
public ToolResult execute(String sessionId, AssistantMessage.ToolCall call)
```

- 按名从工具表查找;找不到 → `ToolResult.fail("unknown tool: ...")` + 审计 success=false。
- 执行前一行:Sandbox 调用位(注释标记,24 节接线)。
- 执行异常 → catch 落审计(success=false + errorMessage)+ 返回 fail 结果,不上抛(错误回填会话,循环继续)。
- 成败都经 `ToolInvocationAudit.record(...)` 落 `tool_invocations`。

## AgentService(oryxos-core)

```java
public String process(Session session, String userMessage)
```

`ProfileRegistry.find` 取 Profile(不存在 → 报错)→ `ProfileContext.set` → try{ `ReActLoop.run`;`SessionManager.save` }finally{ `ProfileContext.clear` }。

## LlmGateway(oryxos-core,D5 端口)

```java
public interface LlmGateway {
  ChatResponse chat(String sessionId, Profile profile, Prompt prompt);
}
```

`ProviderService implements LlmGateway`(16 节类名/签名不动,仅加 implements)。

## ToolInvocationAudit(oryxos-core,D5 端口)

```java
public interface ToolInvocationAudit {
  void record(String sessionId, String profileName, String toolName, String parameters,
      boolean success, String result, String errorMessage, long latencyMs);
}
```

storage 侧 `JpaToolInvocationAudit` 实现;自身写库失败仅记日志不阻断(与 LlmCallAudit 同口径)。

## ContextLoader(oryxos-core)

```java
public String load(Profile profile)
```

读 `.oryxos/` 根下 bootstrap 文件(缺失 WARN)+ `.oryxos/skills/<name>/SKILL.md`(缺失抛 IllegalStateException);每次重读,无缓存。

## OryxTool 改造点(D4,16 节接口)

```java
ToolResult execute(String argumentsJson);   // 新增;原有三个 schema 方法不动
```
