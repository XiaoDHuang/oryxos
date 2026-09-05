# Research: 定时任务（第三种触发源）

本特性无 NEEDS CLARIFICATION 遗留（specify 前用户已决议注册范围）。以下为技术核实与设计决策，全部基于本地锁定依赖实测。

## R1：Spring 调度 API 可用性（H3 核实）

- **Decision**: 使用 `ThreadPoolTaskScheduler.schedule(Runnable, Trigger)` + `new CronTrigger(cron, ZoneId)`。
- **证据**: 锁定 BOM Spring Boot 3.5.16 → spring-context 6.2.19，`javap` 实测：
  - `CronTrigger(String)` / `CronTrigger(String, TimeZone)` / **`CronTrigger(String, ZoneId)`** 三个构造器均存在，课件骨架 `new CronTrigger(sc.getCron(), sc.getZoneId())` 可编译。
  - `TaskScheduler.schedule(Runnable, Trigger)` 返回 `ScheduledFuture<?>`，可用 Mockito 打桩。
  - `ThreadPoolTaskScheduler implements TaskScheduler`，非 final，可 mock。
- **Alternatives considered**: `@Scheduled` 注解——cron 编译期写死，违反 FR-001 配置驱动，课件与技术方案 §8.5 均明确排除；自写调度轮询线程——重复造轮子，排除。

## R2：调度器 Bean 来源

- **Decision**: 直接注入 Spring Boot 自动装配的 `ThreadPoolTaskScheduler`，不自定义 Bean。
- **证据**: spring-boot-autoconfigure 3.5.16 jar 内含 `TaskSchedulingAutoConfiguration`（及 `TaskSchedulerConfiguration`），在容器无 `TaskScheduler`/`ScheduledExecutorService` Bean 时自动提供一个 `ThreadPoolTaskScheduler`；当前代码库无任何自定义 TaskScheduler（grep 无结果）。
- **注意**: 自动装配池大小默认 1，多条规则串行触发；与"锁被占直接跳过"语义叠加后行为可预期，核心阶段可接受。

## R3：注册参数的时区断言方式

- **Decision**: 注册测试用 `ArgumentCaptor<Trigger>` 捕获后断言 `isEqualTo(new CronTrigger(cron, ZoneId.of(zone)))`。
- **Rationale**: `CronTrigger` 只有 `getExpression()`，无时区 getter，但实现了 `equals`（覆盖表达式+时区）。等值断言比逐字段断言更强，且避免反射。
- **Alternatives considered**: 断言 `toString()` 文本——依赖未文档化的字符串格式，脆；弃。

## R4：装配模式（POJO + Configuration）

- **Decision**: `AgentScheduler` 保持纯 POJO（无 `@Component`），在 `CoreEngineConfiguration` 增加 `agentScheduler` @Bean 方法装配；`registerAll()` 由装配方法按启用信号决定是否调用。
- **Rationale**: 代码库既有模式（CoreEngineConfiguration javadoc 明示"引擎类刻意保持纯 POJO，单测容易"）；课件 `@Component`/`@PostConstruct` 写法是示意，落库以本仓装配模式为准。
- **Alternatives considered**: 按课件字面用 `@Component + @PostConstruct`——破坏模块既有装配一致性，且无法用 Environment 控制注册时机；弃。

## R5：仅常驻模式注册的接线（用户决议落地）

- **Decision**: `ServeCommand`/`GatewayCommand` 调 `SpringRuntime.start(...)` 时追加程序参数 `--oryxos.scheduler.enabled=true`；`ChatCommand` 不传。`CoreEngineConfiguration.agentScheduler` @Bean 方法标注 `@ConditionalOnProperty(prefix = "oryxos.scheduler", name = "enabled", havingValue = "true")`：属性缺省时容器中不生成该 Bean，定时触发注册数恒为零；命中时方法体无条件 `registerAll()`。
- **Rationale**: 复用既有 `SpringRuntime.start(boolean, String...)` 通道与 Spring Boot 命令行属性源，零新机制；配置键 `oryxos.scheduler.enabled` 是本次用户决议批准的交付物外小接线。
- **修正记录（实现期回归驱动）**: 初版用 `Environment` 直读属性、Bean 恒创建只门控 `registerAll()`。该写法让 oryxos-tool 既有夹具 `ToolConfigurationTest.CoreConsumers`（继承 CoreEngineConfiguration 验证 toolTable 注入）在缺 `ThreadPoolTaskScheduler` Bean 的 ApplicationContextRunner 里启动失败（前序节契约回归 2 例）。复查依赖树发现 `spring-boot-autoconfigure` 本就在 oryxos-core 编译 classpath（spring-boot-starter 传递），初版"core 不依赖 autoconfigure 故不用 @ConditionalOnProperty"的排除理由不成立；改为条件装配后 Bean 在缺省属性时根本不生成，夹具自然恢复绿，且语义更强（chat 连 Bean 都不存在）。
- **Alternatives considered**: 所有命令无条件注册（课件字面）——被用户决议否决（对齐 §8.6）；Environment 直读门控 registerAll——见修正记录，弃。

## R6：ScheduleConfig 落位与 Profile 强类型化

- **Decision**: `ScheduleConfig` 为顶层 record 放 `com.oryxos.core.profile`（id/cron/zone/message 四 String 分量，`@JsonIgnoreProperties(ignoreUnknown = true)`）；`Profile.schedules` 由 `List<Map<String, Object>>` 改为 `List<ScheduleConfig>`，规范构造器照旧 null→空表。
- **Rationale**: 课件交付物点名 ScheduleConfig（id/cron/zone/message）；放 profile 包使依赖方向 schedule → profile 单向无环。zone 以 String 存储，`ZoneId.of(...)` 在注册时转换——非法时区由此暴露并按 FR-008 跳过。
- **影响面**: `Profile.schedules()` 签名变化。全库 grep 仅 `ProfileLoaderTest` 消费该字段（断言 `containsEntry("name", ...)`），夹具改为四要素即可；`CoreEngineConfigurationTest.profile()` 传 null 不受影响。无生产代码消费旧形态（007 前 schedules 仅透传）。
- **Alternatives considered**: 保持 Map 透传、调度器内手工取值——无编译期契约、校验散落；嵌套为 `Profile.ScheduleConfig`——与 schedule 包互相引用造成包环；均弃。

## R7：锁键与规则 id 唯一性

- **Decision**: 锁表键为裸规则 id（对齐课件 `taskLocks.computeIfAbsent(sc.getId(), ...)` 与 harness `lockFor("task-1")`）；注册期强制 id 全实例唯一，重复者记错误日志跳过（spec FR-008 / Edge Cases 已固化）。
- **Rationale**: 若按 profile:id 复合键，`lockFor("task-1")` 与 runOnce 内部键不一致会导致 harness 跳过测试失效；以唯一性约束根除 id 冲突，比改键简单且语义更清晰。

## R8：非法规则处置时机

- **Decision**: 四要素空白、cron 非法（`CronTrigger` 构造抛异常）、时区非法（`ZoneId.of` 抛 `DateTimeException`）、id 重复——全部在 `registerAll()` 注册期逐条捕获、记中文错误日志、跳过该条，不阻断启动与其他规则。
- **Rationale**: 对齐 §8.2 ProfileLoader "校验失败不阻断启动但记录错误日志" 的既有哲学；加载期（ProfileLoader）不做 cron/时区校验，保持加载器纯粹，调度语义归调度器。

## R9：语法禁区

- **Decision**: 不使用增强 switch 的 `default ->` 等 P3C/ASM 解析不了的 Java 18+ 形态；本特性无 switch 需求，常规 if/try 即可。
