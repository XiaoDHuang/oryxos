# Phase 0 Research: CLI 与会话持久化

## R1 消息历史序列化回读(最需核实的一条)

- **Decision**: `messages_json` 存 `[{role, content, toolCalls?[]}]`;回读按 role 重建:`user`→`UserMessage`,`assistant`→`AssistantMessage.builder().content().toolCalls(...)`,`tool`→`ToolResponseMessage.builder().responses(...)`;未知角色抛错(fail-loud,不静默吞成 user)。system 消息按设计不进 Session(PromptBuilder 每轮现拼),序列化格式不含该角色。
- **核实证据**:javap 1.1.8 确认 `AssistantMessage.builder().content().toolCalls().build()` 与 `ToolResponseMessage.builder().responses().build()` 均存在(17 节已核 ToolResponse 三参 record)。
- **Alternatives considered**: Java 原生序列化(弃:不可读、版本脆);Spring AI 消息直接 Jackson(弃:接口多态+protected 构造,反序列化要定制一堆)。

## R2 轻重分流的判定与 Picocli 形态

- **Decision**: 沿用课件标准「要不要调模型/跑引擎」;cli 模块已是纯 Picocli(无 spring starter,pom 注释写明缘由),重命令在命令内 `SpringApplication.run(OryxOsApplication.class)` 取上下文取 Bean。
- **核实证据**:oryxos-cli/pom.xml 已有 picocli 依赖与既有 InitCommand/VersionCommand 同构。
- **Rationale**: 与课件「别自己解析参数」一致;轻命令全程不进 Spring。

## R3 JPA 扫描范围(课件坑)

- **Decision**: `OryxOsApplication` 显式 `@EnableJpaRepositories("com.oryxos.storage")` + `@EntityScan("com.oryxos.storage")`。
- **Rationale**: 课件点名「scanBasePackages 与自动配置扫描是两套逻辑」;虽然当前包结构下默认恰好能扫到,显式声明把运气变成约定。

## R4 Session 持久化形态

- **Decision**: 运行时 `Session`(core,17 节)不动;storage 侧 `SessionEntity`(sessions 表)+ `SessionRepository` + `JpaSessionManager implements SessionManager`(getOrCreate/get/save)。删除 17 节占位 `InMemorySessionManager`(17 节验收报告已预告本节升级,不留死代码)。
- **id 拼接**:`channel + ":" + user + ":" + profileName`,只在 JpaSessionManager 内;三个分量必须非空且禁止冒号,从输入侧消除分隔符碰撞。
- **Alternatives considered**: 实体也叫 Session(弃:与 core 运行时 Session 同名跨模块,引用处必混乱);id 用 hash(弃:可读性差了排查难,无收益)。

## R5 轻命令的数据来源

- **Decision**: `status` 查工作区;`profile *` 走 `.oryxos/profiles/` 文件;`provider list`/`tool list` 扫 profiles YAML 汇总(不调引擎,符合课件轻命令标准);`session list` 直连 SQLite JDBC 读 sessions 表(不启动 Spring)。
- **Rationale**: 这些命令调的是配置面/存储面,不是引擎;课件把「轻=不起 Spring」作为体感质量硬要求。
