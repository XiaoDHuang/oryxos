# 009-agent-scheduler 验收归档（第 25 节：定时任务模块）

**归档日期**：2026-09-05　**分支**：`025-lesson25-scheduler`　**关联课件**：`docs/class/第25节：定时任务模块 原理解析、实现与代码讲解.md`

## 交付物

- 代码：`oryxos-core` 新增 `schedule/AgentScheduler.java`（registerAll + runOnce + 按规则 id 的 ReentrantLock 表 + 包私有 lockFor）、`profile/ScheduleConfig.java`（id/cron/zone/message）；修改 `Profile.java`（schedules 强类型化）、`ProfileRegistry.java`（新增 all()）、`config/CoreEngineConfiguration.java`（@ConditionalOnProperty 条件装配）
- 接线：`oryxos-cli` 的 `ServeCommand`/`GatewayCommand` 追加 `--oryxos.scheduler.enabled=true`；chat 不传（用户决议，对齐技术方案 §8.6）
- 测试：`AgentSchedulerTest` 11 例；`CoreEngineConfigurationTest` +2；`ProfileLoaderTest` 夹具适配
- 文档：本目录七件产物 + `AGENTS.md` 项目现状同步 + `dependency-check-suppressions.xml` 两条 CVE 处置

## 六项证据 DoD

1. `mvn clean verify` 全绿：EXIT=0，9 模块 + parent 全 SUCCESS，6m53s（Spotless/Checkstyle/P3C/PMD/SpotBugs/FindSecBugs/OWASP 全过）
2. harness 对号：课件四回归点全覆盖——注册携带 cron+时区（CronTrigger 等值断言）、锁占跳过（never()）、异常不外抛+finally 放锁（二进宫 times(2)）、三元组固定+同一 Session；另含 FR-008 三例与空 schedules/多 Profile 两例
3. 交付物存在性核对通过（见上）
4. 前序节回归全绿：tool 146、memory 133、storage 16、provider 8、web 10、cli/channel-cli 12
5. H4 六条不变量自查通过：本节无涉外 IO；审计走既有 AgentService.process 链路；无凭证；session_id 只在 SessionManager 内拼接；无 Reactor/CompletableFuture/业务自建线程池；未触碰 Spring AI 自动工具执行
6. 人工项移交（quickstart.md 四步）：真实到点触发看审计账、chat 不注册体感、改 cron 重启生效、非法 zone 跳过不崩

## 过程决策与修正记录

- **注册范围**：specify 前用户决议仅常驻模式（serve/gateway）注册，chat 不注册（FR-002/SC-006）
- **条件装配替代 Environment 门控**：初版 Bean 恒创建只门控 registerAll()，致 24 节 `ToolConfigurationTest.CoreConsumers` 夹具上下文缺 ThreadPoolTaskScheduler 起不来；复查确认 spring-boot-autoconfigure 本在 core 编译 classpath，改 @ConditionalOnProperty 后缺省属性连 Bean 都不生成，夹具恢复绿（research.md R5 修正记录）
- **课件 harness 修正**：锁占跳过用例改由另一线程持锁——课件单线程 lock() 写法对可重入 ReentrantLock 测不到跳过语义（同线程 tryLock 必成功）；断言逐字保真（AgentSchedulerTest 注释有说明）
- **锁键裸 id + 注册期全实例唯一约束**：对齐课件 lockFor/runOnce 键一致性，根除跨 Profile 撞 id 误互斥（spec FR-008）
- **CVE 处置**：NVD 源新增 spring-ai-model 1.1.8 两条 7.5 告警（CVE-2026-47851 PDF ingestion / CVE-2026-47852 ONNX 缓存），经全仓 grep + reactor artifact 清单双重核实不可达，按 007 R5 先例逐条抑制；修复线在 Spring AI 2.0.0，升级属平台决策不在本节范围
- **静态门禁抑制**：三处 CRLF_INJECTION_LOGS（值为管理员本地 YAML，可信配置）+ 一处 EI_EXPOSE_REP2（容器单例协作对象），均带 justification，与 CliChannel/LlmCallAudit 先例同构

## 边界（本节明确不做）

分布式协调（选主/分布式锁/租约）、失败自动重试与告警、运行时增删定时任务接口——均扩展阶段。
