# Contract: 运行时注册 Java 契约（进程内）

本节无 REST/CLI 新增。以下为 `oryxos-core` 进程内公开契约，字面量以此为准。

## ProfileRegistry（core.profile，16 节改造点）

```java
/** 运行时注册:先过与启动加载完全同一套校验,失败抛同一异常同一消息. */
public synchronized void register(Profile profile)   // 非法抛 IllegalArgumentException

/** 按名移除;不存在为空操作. */
public synchronized void remove(String name)

/** 按名判断是否已注册. */
public synchronized boolean exists(String name)
```

- 既有 `find(String)` / `all()` / 构造器签名不变；内部 Map 保序，public 方法 `synchronized`。
- 校验规则（`ProfileValidator`，package-private，两条来源共用）：
  - `name` 空/空白 → `IllegalArgumentException`，消息点名缺少必填字段 `name`；
  - `provider.name` 不在全局 provider 名集合 → `IllegalArgumentException`，消息点名 provider 名与"未在全局 provider 层声明"。
  - 启动路径（`ProfileLoader`/`AgentLoader`+scanner）catch 该异常、按既有格式记错误日志并跳过；运行时路径（`register`）直接上抛。**异常类型与消息两条路径逐字一致**（ProfileRegistryRuntimeTest 守点）。

## AgentScheduler（core.schedule，25/28 节改造点）

```java
/** 注册单个 Profile 的全部 schedules:校验→Store 登记→catalog→安装 cron 句柄.
 *  非法规则记错误日志跳过该条,不阻断其余;重复 id 后者跳过(与 registerAll 同规则). */
public void registerProfile(Profile profile)
```

- `registerAll()` 语义不变：先取消本实例旧安装并清表 → 逐 Profile `registerProfile` → 定义消失清扫。
- 句柄表 = 既有 `cronFutures`（`Map<String, ScheduledFuture<?>>`，按任务 id）；`registerProfile` 后句柄在表（AgentSchedulerRegisterTest 守点），30 节注销/更新用。
- cron/时区/触发消息只来自 `Profile.schedules`，scanner/调用方不得另造来源。

## AgentLoader（core.agent，新增）

```java
/** 解析一个 Agent 目录:读 AGENT.md,拆 frontmatter(归一化+ENV 解析)与正文,记录资源位置.
 *  缺 AGENT.md / 坏 YAML 抛 IllegalArgumentException 并点名目录. */
public AgentDefinition load(Path agentDir)

/** load + 映射:frontmatter 派生成 Profile(identity.promptFile = agents/<目录名>/AGENT.md). */
public Profile deriveProfile(Path agentDir)
```

## AgentDirectoryScanner（core.agent，新增）

```java
/** 扫描 agentsDir 全部子目录:逐个 load→deriveProfile→(exists 检查)→register→
 *  (供入了 scheduler 且有 schedules 时)registerProfile. 单目录失败记错误日志跳过,不阻断其余. */
public int scan(Path agentsDir)   // 返回成功注册数;目录不存在/为空返回 0
```

构造供入：`ProfileRegistry`、`ProfileValidator`、已注册工具名集合（`null`=无法判定，跳过工具告警）、`AgentScheduler`（`null`=无调度器模式，跳过定时注册）。

## ContextLoader（core.context，加法）

`load(Profile)` 增加：`identity.promptFile` 非空时，从工作区根现读该文件；内容以 `---` 行开头且有闭合 `---` 行时剥去 frontmatter，正文段拼入上下文。文件缺失抛 `IllegalStateException`（与显式 bootstrap 引用缺失同级）。无缓存契约不变。
