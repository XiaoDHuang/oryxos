# Phase 0 Research: ReAct 循环

## R1 Spring AI 消息 API(课件骨架的类型落地)

- **Decision**: 循环与 Session 直接用 Spring AI 消息类型:`AssistantMessage.getToolCalls()`/`hasToolCalls()` 判断停止条件;工具结果用 `ToolResponseMessage.builder().responses(...)` 回填;`AssistantMessage.ToolCall` 为 4 字段 record(id/type/name/arguments,访问器同名)。
- **核实证据**:javap 1.1.8 `spring-ai-model` jar 逐项确认(见会话记录)。
- **Alternatives considered**: 自建 ToolCall/消息体系(弃:与 Provider 返回的 ChatResponse 不兼容,且 16 节已定 Spring AI 为消息基座)。

## R2 core 的循环依赖问题(最重要的一条)

- **现象**:`ReActLoop`(core)要调 `ProviderService`(provider),`ToolExecutor`(core)要写 `tool_invocations`(storage);而 provider、storage 均已依赖 core——直接引用具体类 = Maven 循环依赖,编译不过。
- **Decision**: core 定义端口接口 `LlmGateway`(签名与 16 节已定 `chat(sessionId, Profile, Prompt)` 逐字一致,`ProviderService` 加 `implements` 即可,类名/方法名不动)与 `ToolInvocationAudit`(record 方法,storage 侧 `JpaToolInvocationAudit` 实现)。
- **Rationale**: 六边形端口是标准解;保持「core 无下游」的依赖方向(AGENTS.md:不要让 ReAct 循环反向依赖外围)。
- **Alternatives considered**: ProviderService 下沉 core(违反 16 节落位);core 依赖 provider(循环);把 ReActLoop 挪到 provider(违反本节落位表)。

## R3 tool_invocations 列对齐

- **现象**:课件称该表「本来就有 success/error_message」,实际现状是 status/result/error(oryxos-init 按旧数据模型建的)。
- **Decision**: 按课件补齐 `success INTEGER NOT NULL`、`error_message TEXT`,保留既有列;同 16 节 llm_calls 的处理口径。
- **Rationale**: 课件为本节权威输入;「成败对称审计」是两节共同红线。

## R4 历史截断口径

- **Decision**: 按 messages 条数保留末尾 N 条(N=maxHistoryTurns,默认 20),system prompt 不计入;精确到「轮」的语义 18 节收紧。
- **Rationale**: 课件只要求「超 N 截断」可测试;条数截断是最简确定性实现。

## R5 ContextLoader 缺失处理分级

- **Decision**: `skills` 引用(`.oryxos/skills/<name>/SKILL.md`)缺失 → 抛 `IllegalStateException`(报错);`bootstrap` 文件缺失 → WARN 日志跳过;每次调用重新读盘,无缓存。
- **核实证据**:课件 17 节原文「Profile 里显式引用的文件缺失要报错、Bootstrap 缺失至少 WARN」。

## R6 ProfileContext 线程模型

- **Decision**: `ThreadLocal<Profile>` 静态 set/current/clear;虚拟线程下每请求独占载体线程,finally 必清(回归测试钉死异常路径)。
- **Alternatives considered**: 改 `OryxTool.execute` 签名带 Profile(弃:课件明确「改工具接口签名代价太大」)。
