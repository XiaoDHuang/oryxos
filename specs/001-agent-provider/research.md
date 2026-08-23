# Phase 0 Research: Agent Provider

## R1 Spring AI 依赖可用性(课件坑三:先验证再接)

- **Decision**: 使用 BOM 管理的 `spring-ai-openai`(**库 jar,不是 starter**)。初版用 `spring-ai-starter-model-openai`,真实启动冒烟发现其自动配置会自行创建 `openAiAudioSpeechModel` 等 Bean(缺 `spring.ai.openai.api-key` 时直接启动失败;配了 key 则容器里会多出一套自动配置的 ChatModel——恰好踩中坑一「多个 ChatModel Bean 分不清谁是谁」)。显式映射设计与 starter 自动配置互斥,故降为库依赖。
- **核实证据**:`mvn dependency:get` 成功;`spring-ai-bom-1.1.8.pom` 管理该 artifact;修正后应用真实启动 health=UP。
- **注意**:Spring AI 1.1.x 模块已重组——`spring-ai-core` 1.1.8 在 central 无 jar;工具/模型抽象在 `spring-ai-model`,客户端在 `spring-ai-client-chat`。依赖一律按 BOM 引导,不手写 `spring-ai-core` 坐标。
- **Alternatives considered**: `spring-ai-starter-model-openai`(弃:其自动配置会自建 ChatModel/音频等 Bean,与显式映射互斥);`spring-ai-starter-model-deepseek`(DeepSeek 专用,弃:Kimi 不能用同一套);Spring AI Alibaba 各 connector(弃:核心阶段不铺开)。

## R2 关闭自动工具执行的 API(课件坑二)

- **Decision**: `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)`,随每次 `ChatModel.call(Prompt)` 的 options 传入。
- **核实证据**:javap 1.1.8 jar 确认 `OpenAiChatOptions.get/setInternalToolExecutionEnabled`、`ToolCallingChatOptions$Builder.internalToolExecutionEnabled(Boolean)` 均存在。
- **Alternatives considered**: 全局配置关闭(无此全局开关,只能 per-request);不给 tools(破坏 FR-005)。

## R3 工具 schema 翻译(只翻译不执行)

- **Decision**: 适配器把 `OryxTool.getInputSchema()` 等字段组装成 `ToolDefinition`(name/description/inputSchema,`DefaultToolDefinition.builder()`),通过 options 的 toolCallbacks/toolNames 机制随请求下发;`internalToolExecutionEnabled=false` 保证模型只返回调用意图。
- **核实证据**:javap 确认 `ToolDefinition` 三字段契约与 `ToolCallingChatOptions` 的 `toolCallbacks`/`toolNames`。
- **Alternatives considered**: 自建 JSON 拼 OpenAI 报文(弃:重复造协议转换,违反课件「不自己造」)。

## R4 Provider 多实例构建方式

- **Decision**: 不用 Spring AI 自动配置的多 Bean,改为自己按 `oryxos.providers` 列表逐条 `OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().baseUrl(...).apiKey(...).build()).build()`,put 进显式 `Map<String, ChatModel>`。凭证缺失在构建时报错(clarify 结论)。
- **Rationale**: 显式映射表是课件坑一的正解;自动配置的 Bean name 不可靠。
- **Alternatives considered**: `@Qualifier` 区分自动配置 Bean(弃:Bean name 未必等于 provider name,课件明确否定)。

## R5 llm_calls 表结构演进

- **Decision**: 手工脚本为 `llm_calls` 增 `success INTEGER NOT NULL`、`error_message TEXT`;保留原有 `status` 列;沿用 `CREATE TABLE IF NOT EXISTS` 幂等写法。
- **Rationale**: 课件硬性要求两列;SQLite ALTER 弱,直接改 CREATE 脚本(库文件由 `.oryxos/` 重建,核心阶段无存量数据迁移负担)。
- **Alternatives considered**: 用 `status` 字符串兼任成功标识(弃:课件明确要 `success`/`error_message`,与 tool_invocations 对称)。

## R6 审计写入失败的处理

- **Decision**: audit 写入包 try/catch,失败记 ERROR 日志,不阻断 LLM 调用主流程(spec Edge Case 已定)。
