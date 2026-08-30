# Quickstart: 第22节 Memory 验证

## Prerequisites

- JDK 21、Maven 3.9.x。
- 从仓库根目录执行命令。
- 默认单测不得依赖真实 LLM、外部 Memory 服务或用户主目录。
- 文件测试必须使用 `@TempDir` 注入显式 workspace，不修改全局 `user.dir`。

## 1. 依赖与模块方向

```powershell
mvn -pl oryxos-tool -am dependency:tree "-Dincludes=com.oryxos:oryxos-memory,org.springframework.ai:spring-ai-model"
```

预期：`oryxos-tool → oryxos-memory → oryxos-core` 单向可解析；Spring AI 版本为父 POM 锁定版本；九模块不变。

## 2. 本节默认单测

```powershell
mvn -pl oryxos-memory -am test
```

预期：Memory模块`LongTermMemoryTest` 14项、`MemoryServiceImplTest` 5项、`MemoryToolsTest` 6项，共25项失败/错误/跳过为0；依赖模块测试同样全绿。

## 3. 关键课件守点

```powershell
mvn -pl oryxos-memory -am test "-Dtest=LongTermMemoryTest#truncationOnlyAffectsArchiveAndPreservesCore+writesAreVisibleImmediatelyWithoutCache" "-Dsurefire.failIfNoSpecifiedTests=false"
```

若当前 Surefire 不接受方法列表中的空格，使用：

```powershell
mvn -pl oryxos-memory -am test "-Dtest=LongTermMemoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

预期：核心记忆完整、最早归档不在注入视图、最近归档保留；写入后 load/recall 立即可见。

## 4. Prompt 与 Tool 注册回归

```powershell
mvn -pl oryxos-tool -am test "-Dtest=PromptBuilderTest,ToolConfigurationTest,MemoryToolsTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

预期：Memory System Message 位于最近历史之前；`save_memory` / `recall_memory` 作为内置 Tool 可执行；普通 Java Plugin 仍默认拒绝。

## 5. 显式 Boot 集成

```powershell
mvn -pl oryxos-boot -am test "-Dtest=MemorySystemIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dgroups=integration" "-Dtest.excludedGroups=__none__"
```

预期：执行1项。第一套 Spring 上下文的新 Session 经 ReAct/ToolExecutor 调用 `save_memory` 后关闭；第二套上下文复用同一 workspace/SQLite 并创建不同 Session，Prompt 自动带上已保存核心记忆，再调用 `recall_memory`。临时 SQLite 中 save/recall 各有且仅有一条最终成功审计。

## 6. 全量门禁

开发阶段快速门禁：

```powershell
mvn clean verify "-Ddependency-check.skip=true"
```

封板前完整门禁：

```powershell
mvn clean verify
```

预期：默认228项测试及 Spotless、P3C、Checkstyle、PMD、SpotBugs/FindSecBugs、OWASP Dependency-Check 全绿。快速门禁不能替代完整门禁。

## 7. 人工验证

1. 在临时目录运行 `oryxos init`，确认 `.oryxos/memory/MEMORY.md` 精确包含核心/归档两个区块。
2. 用真模型说出一条值得长期保存的偏好，确认 Agent 主动调用 `save_memory`。
3. 新开会话或重启后再次提问，确认 Prompt 带上该记忆并影响回复。
4. 目检 `USER.md` 未变化，Memory 文件写入正确 scope。
5. 目检 `tool_invocations` 对 save/recall 各逻辑调用只记录最终一次结果。

可选的外部 Memory 服务、语义检索和多租户隔离不属于本节人工项。
