package com.oryxos.core.schedule;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 第三种触发源(钟推):到点把 Profile 声明的定时规则派发给与人推完全相同的 {@link AgentService#process} 入口. 009 只"到点喊一声";011 把执行挪进
 * Spring 管理的虚拟 worker(cron 线程只派发/更新候选/起 watchdog), 状态与历史全部经 {@link ScheduledTaskStore} 落库:进行中先记账、60
 * 秒本地截止、超时/失败/成功终态唯一, 进程中断由下次启动标 unknown。保持纯 POJO,由 CoreEngineConfiguration 装配(仅常驻模式)。
 *
 * @author OryxOS Contributors
 */
public class AgentScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentScheduler.class);

  /** 钟推的固定 channel:与 user 一起构成固定三元组,同 Profile 历次定时触发复用同一 Session(§8.5). */
  private static final String SCHEDULER_CHANNEL = "scheduler";

  /** 钟推的固定 user(语义见 channel 常量). */
  private static final String SCHEDULER_USER = "scheduler";

  /** 单次执行的公共截止(60 秒);watchdog 起点为实际准入时刻. */
  private static final long DEFAULT_EXECUTION_TIMEOUT_MS = 60000L;

  /** 工具结果元数据键(与 ReActLoop 约定):本轮成败判定的唯一来源,不解析正文文本. */
  private static final String TOOL_SUCCESS_METADATA = "oryxos.tool.success";

  private final ThreadPoolTaskScheduler taskScheduler;
  private final Executor workerExecutor;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ScheduledTaskStore store;

  /** 按规则 id 的进程内锁表:防同一任务重叠执行. 核心阶段单实例,本地锁足够. */
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  /** 按 Profile 的进程内锁表:同 Profile 的规则共享 scheduler Session,必须互斥防丢更新. */
  private final ConcurrentMap<String, Lock> profileLocks = new ConcurrentHashMap<>();

  /** 按任务 id 的短准入互斥:只把"开关检查 + begin 记账"与启停序列化,不持有到引擎结束. */
  private final ConcurrentMap<String, Lock> admissionLocks = new ConcurrentHashMap<>();

  /** 本次启动合法规则的进程内索引(catalog);唯一业务状态在 Store,这里只是可运行性视图. */
  private final ConcurrentMap<String, CatalogEntry> catalog = new ConcurrentHashMap<>();

  /** 本实例安装的 cron future,重复 registerAll 先取消旧安装防重复触发. */
  private final ConcurrentMap<String, ScheduledFuture<?>> cronFutures = new ConcurrentHashMap<>();

  private volatile long executionTimeoutMs = DEFAULT_EXECUTION_TIMEOUT_MS;

  /** 创建调度器. 构造无副作用,注册/恢复全部在显式调用里(装配方在上下文初始化完成后驱动). */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者是 Spring 容器管理的单例 Bean,调度器的本职就是持有并驱动它们,防御性拷贝反而语义错误。")
  public AgentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      Executor workerExecutor,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore store) {
    this.taskScheduler = taskScheduler;
    this.workerExecutor = workerExecutor;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.store = store;
  }

  /** 仅进程启动、cron 安装前调用一次:把上次进程遗留的 running 诚实标记 unknown,不重放. */
  public void recoverInterrupted() {
    store.recoverInterrupted();
  }

  /**
   * 扫描全部 Profile 的 schedules 逐条登记并安装 cron. 非法规则(要素缺失/cron 或时区不可解析/id 重复)记错误日志后跳过,
   * 不阻断启动;重复调用先取消本实例旧安装再重注册,不会重复触发。定义消失的既有任务保留行与历史,候选清空、只读可查。
   */
  public void registerAll() {
    cronFutures.values().forEach(future -> future.cancel(false));
    cronFutures.clear();
    catalog.clear();
    Set<String> registeredIds = new HashSet<>();
    for (Profile profile : profileRegistry.all()) {
      registerProfile(profile, registeredIds);
    }
    // 定义消失(含本次被跳过)的既有任务:行与历史保留,候选清空,只读可查、执行拒绝
    for (ScheduledTaskView stored : store.listTasks()) {
      if (!catalog.containsKey(stored.taskId()) && stored.nextRunAt() != null) {
        store.updateNextRun(stored.taskId(), null);
      }
    }
  }

  /**
   * 注册单个 Profile 的全部 schedules(29 节:启动扫描与运行时新增 Agent 走同一入口). 非法规则记错误日志跳过该条, 不阻断其余;与 catalog 既有任务撞
   * id 按重复跳过(锁按裸 id 持有,撞车会让不同任务互相阻塞)。
   */
  public void registerProfile(Profile profile) {
    registerProfile(profile, new HashSet<>(catalog.keySet()));
  }

  /** 单 Profile 注册主流程(registerAll 循环体原样抽出);句柄表沿用 cronFutures,30 节注销/更新用. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "规则四要素与 profile 名来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入),日志仅落本地运维通道。")
  private void registerProfile(Profile profile, Set<String> registeredIds) {
    for (ScheduleConfig schedule : profile.schedules()) {
      CronTrigger trigger = buildTrigger(profile, schedule, registeredIds);
      if (trigger == null) {
        continue;
      }
      try {
        // 登记进 Store(保留 enabled;停用任务的候选由 Store 强制为 NULL)
        store.register(profile.name(), schedule, nextCandidate(trigger));
      } catch (IllegalArgumentException e) {
        LOGGER.error("定时规则 {} 登记失败,跳过注册: {}", schedule.id(), e.getMessage());
        continue;
      }
      catalog.put(schedule.id(), new CatalogEntry(profile, schedule, trigger));
      ScheduledFuture<?> future = taskScheduler.schedule(() -> triggerCron(schedule.id()), trigger);
      // 测试中未打桩的 schedule 返回 null;null 不入表,避免取消时空指针
      if (future != null) {
        cronFutures.put(schedule.id(), future);
      }
      registeredIds.add(schedule.id());
      LOGGER.info(
          "已注册定时任务 {}(profile={}, cron={}, zone={})",
          schedule.id(),
          profile.name(),
          schedule.cron(),
          schedule.zone());
    }
  }

  /**
   * 立即执行一次任务并等待结果(等待发生在外层调用线程,不占 cron 线程). 找不到任务抛 NoSuchElementException; 历史存在但当前规则失效抛
   * IllegalArgumentException;忙碌抛 RejectedExecutionException 且零新增历史。
   */
  public TaskExecutionView runNow(String taskId) {
    store.findTask(taskId).orElseThrow(() -> new NoSuchElementException("定时任务不存在: " + taskId));
    CatalogEntry entry = catalog.get(taskId);
    if (entry == null) {
      throw new IllegalArgumentException("定时任务当前规则失效,不可执行: " + taskId);
    }
    // 快速预检(非权威,worker 的 tryLock 才是闸门):已占用直接拒,免得白等一轮
    if (((ReentrantLock) lockFor(taskId)).isLocked()) {
      throw new RejectedExecutionException("定时任务正在执行中: " + taskId);
    }
    ExecutionHandle handle = new ExecutionHandle(taskId);
    workerExecutor.execute(
        () -> runWorker(entry.profile(), entry.schedule(), handle, false, false));
    return awaitOutcome(taskId, handle);
  }

  /** 当前规则是否可运行(在本次 catalog 内). */
  public boolean isRegistered(String taskId) {
    return catalog.containsKey(taskId);
  }

  /** 持久化启停. 与 begin 经同一任务的短准入互斥序列化:先开始的执行继续跑完(不等 worker 结束), 先停用的自动触发被跳过。不修改 cron/message。 */
  public ScheduledTaskView setEnabled(String taskId, boolean enabled) {
    store.findTask(taskId).orElseThrow(() -> new NoSuchElementException("定时任务不存在: " + taskId));
    Lock admission = admissionLockFor(taskId);
    admission.lock();
    try {
      if (enabled) {
        CatalogEntry entry = catalog.get(taskId);
        Instant next = entry == null ? null : nextCandidate(entry.trigger());
        return store.setEnabled(taskId, true, next);
      }
      return store.setEnabled(taskId, false, null);
    } finally {
      admission.unlock();
    }
  }

  /** Cron 回调:只做派发——更新候选时间、把执行交给 worker,绝不在调度线程里等引擎. */
  void triggerCron(String taskId) {
    CatalogEntry entry = catalog.get(taskId);
    if (entry == null) {
      return;
    }
    store
        .findTask(taskId)
        .ifPresent(
            task -> {
              if (task.enabled()) {
                store.updateNextRun(taskId, nextCandidate(entry.trigger()));
              }
            });
    ExecutionHandle handle = new ExecutionHandle(taskId);
    workerExecutor.execute(() -> runWorker(entry.profile(), entry.schedule(), handle, true, true));
  }

  /**
   * 单次触发(测试入口,009 语义):不走 catalog/enabled 检查,直接派发一条给定规则. 拿不到锁说明上次没跑完, 本次跳过不排队;失败只记日志,锁在 worker
   * finally 必放。
   */
  void runOnce(Profile profile, ScheduleConfig schedule) {
    ExecutionHandle handle = new ExecutionHandle(schedule.id());
    workerExecutor.execute(() -> runWorker(profile, schedule, handle, true, false));
  }

  /**
   * Worker 主流程:自取自放任务锁与 Profile 锁(线程亲和)→ 短准入互斥内做开关检查与 begin → 跑引擎 → 分类终态. watchdog 从 begin
   * 的真实准入时刻起算,与 worker 竞争条件终态,先到者胜.
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "同上:任务 id 来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入)。")
  private void runWorker(
      Profile profile,
      ScheduleConfig schedule,
      ExecutionHandle handle,
      boolean automatic,
      boolean checkEnabled) {
    String taskId = schedule.id();
    ReentrantLock taskLock = (ReentrantLock) lockFor(taskId);
    if (!taskLock.tryLock()) {
      if (automatic) {
        LOGGER.info("定时任务 {} 上一次仍未结束,本次触发跳过", taskId);
        return;
      }
      handle.reject(new RejectedExecutionException("定时任务正在执行中: " + taskId));
      return;
    }
    ReentrantLock profileLock = (ReentrantLock) profileLockFor(profile.name());
    if (!profileLock.tryLock()) {
      taskLock.unlock();
      if (automatic) {
        LOGGER.info("定时任务 {} 所属 Profile 有任务在执行,本次触发跳过", taskId);
        return;
      }
      handle.reject(new RejectedExecutionException("同 Profile 有定时任务正在执行: " + taskId));
      return;
    }
    try {
      handle.workerThread = Thread.currentThread();
      Session session;
      Lock admission = admissionLockFor(taskId);
      admission.lock();
      try {
        if (checkEnabled) {
          ScheduledTaskView current =
              store
                  .findTask(taskId)
                  .orElseThrow(() -> new NoSuchElementException("定时任务不存在: " + taskId));
          if (!current.enabled()) {
            LOGGER.info("定时任务 {} 已停用,本次自动触发跳过", taskId);
            return;
          }
        }
        session = sessionManager.getOrCreate(SCHEDULER_CHANNEL, SCHEDULER_USER, profile.name());
        handle.execution = store.begin(taskId, session.id(), Instant.now());
      } finally {
        admission.unlock();
      }
      scheduleWatchdog(taskId, handle);
      int beforeCount = session.messages().size();
      Outcome outcome;
      try {
        agentService.process(session, schedule.message());
        outcome = classify(session, beforeCount);
      } catch (Exception e) {
        LOGGER.error("定时任务 {} 模型调用失败", taskId, e);
        outcome = Outcome.failureOutcome(ScheduledTaskStore.ERROR_LLM);
      }
      complete(handle, outcome);
    } catch (RuntimeException e) {
      // begin 失败或意外:引擎绝不启动;自动路径只记日志,runNow 路径经 handle 传回调用方
      LOGGER.error("定时任务 {} 执行失败", taskId, e);
      handle.fail(e);
    } finally {
      profileLock.unlock();
      taskLock.unlock();
    }
  }

  /** Worker 正常收尾:条件终态(迟到不覆盖 timeout)、取消 watchdog、唤醒等待方;终态写失败绝不伪报成功. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "任务 id 来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入),日志仅落本地运维通道。")
  private void complete(ExecutionHandle handle, Outcome outcome) {
    long durationMs = System.currentTimeMillis() - handle.startedMillis;
    try {
      TaskExecutionView finished =
          store.finish(
              handle.execution.executionId(),
              outcome.success(),
              outcome.errorMessage(),
              durationMs);
      if (handle.watchdogTask != null) {
        handle.watchdogTask.cancel(false);
      }
      handle.finishedView = finished;
    } catch (RuntimeException e) {
      LOGGER.error("定时任务 {} 终态写入失败,保留 running 供恢复", handle.taskId, e);
      handle.infraFailure = e;
    }
    handle.latch.countDown();
  }

  /** 60 秒截止:中断 worker、写 timeout 终态、唤醒请求方;不取 worker 的执行锁,锁保留到 worker 真实退出. */
  private void scheduleWatchdog(String taskId, ExecutionHandle handle) {
    Instant deadline = Instant.now().plusMillis(executionTimeoutMs);
    handle.watchdogTask = taskScheduler.schedule(() -> fireWatchdog(taskId, handle), deadline);
  }

  /** Watchdog 落点:正常收尾过就不再写终态;写失败保留 running 供恢复,绝不伪报成功. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "任务 id 来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入),日志仅落本地运维通道。")
  private void fireWatchdog(String taskId, ExecutionHandle handle) {
    // worker 已正常收尾就不再写终态;竞态窗口由 Store 的条件终态兜底,终态唯一
    if (handle.finishedView != null) {
      return;
    }
    Thread worker = handle.workerThread;
    if (worker != null) {
      worker.interrupt();
    }
    try {
      TaskExecutionView finished =
          store.finish(
              handle.execution.executionId(),
              false,
              ScheduledTaskStore.ERROR_TIMEOUT,
              executionTimeoutMs);
      handle.finishedView = finished;
    } catch (RuntimeException e) {
      LOGGER.error("定时任务 {} 超时终态写入失败,保留 running 供恢复", taskId, e);
      handle.infraFailure = e;
    }
    handle.latch.countDown();
  }

  /**
   * 结果分类(本轮增量消息):正常返回 + 末尾是无 toolCalls 的 AssistantMessage + 本轮 ToolResponse 元数据全部 明确 true,才算成功;末尾是
   * ToolResponse 判轮数耗尽;元数据缺失按失败,不解析正文文本.
   */
  private static Outcome classify(Session session, int beforeCount) {
    List<Message> tail = session.messages().subList(beforeCount, session.messages().size());
    if (tail.isEmpty()) {
      return Outcome.failureOutcome(ScheduledTaskStore.ERROR_LLM);
    }
    for (Message message : tail) {
      if (message instanceof ToolResponseMessage toolMessage
          && !Boolean.TRUE.equals(toolMessage.getMetadata().get(TOOL_SUCCESS_METADATA))) {
        return Outcome.failureOutcome(ScheduledTaskStore.ERROR_TOOL);
      }
    }
    Message last = tail.getLast();
    if (last instanceof ToolResponseMessage) {
      return Outcome.failureOutcome(ScheduledTaskStore.ERROR_ITERATIONS);
    }
    if (last instanceof AssistantMessage assistant && !assistant.hasToolCalls()) {
      return Outcome.successOutcome();
    }
    return Outcome.failureOutcome(ScheduledTaskStore.ERROR_LLM);
  }

  /** 等待 runNow 结果:本地 1.5 倍截止兜底(存储阻塞也不无限等);忙碌/基础设施失败上抛,不包装成业务失败. */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "runNow 的契约异常(忙碌/截止/记账失败)是面向调用方的公开失败语义,按 011 契约原样上抛。")
  private TaskExecutionView awaitOutcome(String taskId, ExecutionHandle handle) {
    try {
      boolean done =
          handle.latch.await(
              Math.max(1L, executionTimeoutMs + executionTimeoutMs / 2), TimeUnit.MILLISECONDS);
      if (!done) {
        throw new IllegalStateException("定时任务执行等待超过本地截止: " + taskId);
      }
    } catch (InterruptedException e) {
      // 等待线程被中断的典型场景是 MVC 异步超时掐断请求线程;此时 watchdog 大概率刚落下 timeout
      // 终态。先别急着重置中断标记(sleep 会立刻再抛),有界轮询 Store 取回真实终态,按固定分类
      // 交给调用方映射超时;取不到才按基础设施错误上抛。
      TaskExecutionView execution = handle.execution;
      if (execution != null) {
        long readDeadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < readDeadline) {
          try {
            var latest = store.listExecutions(taskId, 0, 1);
            if (!latest.isEmpty()
                && latest.getFirst().executionId() == execution.executionId()
                && ScheduledTaskStore.ERROR_TIMEOUT.equals(latest.getFirst().errorMessage())) {
              Thread.currentThread().interrupt();
              return latest.getFirst();
            }
            Thread.sleep(50);
          } catch (InterruptedException interruptedAgain) {
            break;
          }
        }
      }
      Thread.currentThread().interrupt();
      throw new IllegalStateException("定时任务执行等待被中断: " + taskId, e);
    }
    if (handle.rejected != null) {
      throw handle.rejected;
    }
    if (handle.failure != null) {
      throw handle.failure;
    }
    if (handle.infraFailure != null) {
      throw new IllegalStateException("定时任务终态记账失败: " + taskId, handle.infraFailure);
    }
    if (handle.finishedView == null) {
      throw new IllegalStateException("定时任务终态缺失: " + taskId);
    }
    return handle.finishedView;
  }

  /** 构造触发器;任何一步非法都返回 null 并由本方法记日志,调用方据此跳过. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "同上:被记录的非法值全部来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入)。")
  private CronTrigger buildTrigger(
      Profile profile, ScheduleConfig schedule, Set<String> registeredIds) {
    if (isBlank(schedule.id())
        || isBlank(schedule.cron())
        || isBlank(schedule.zone())
        || isBlank(schedule.message())) {
      LOGGER.error("定时规则四要素(id/cron/zone/message)均不得为空,跳过注册: profile={}", profile.name());
      return null;
    }
    if (registeredIds.contains(schedule.id())) {
      // 锁按裸 id 持有,id 撞车会让不同任务互相阻塞,必须以唯一性约束根除
      LOGGER.error("定时规则 id 重复: {}, 后者跳过注册(profile={})", schedule.id(), profile.name());
      return null;
    }
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(schedule.zone());
    } catch (DateTimeException e) {
      LOGGER.error("定时规则 {} 的时区不可识别: {}, 跳过注册", schedule.id(), schedule.zone());
      return null;
    }
    try {
      // cron 与时区一起传入,不让服务器系统时区替用户做主
      return new CronTrigger(schedule.cron(), zoneId);
    } catch (IllegalArgumentException e) {
      LOGGER.error("定时规则 {} 的 cron 表达式非法: {}, 跳过注册", schedule.id(), schedule.cron());
      return null;
    }
  }

  /** 取任务 id 对应的锁(不存在则建). 包私有:harness 用它模拟"上一次还占着锁". */
  Lock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  /** 任务 id 的 cron 句柄是否在表(29 节句柄表守点). 包私有:harness 专用,生产路径经 isRegistered 判断可运行性. */
  boolean hasCronHandle(String taskId) {
    return cronFutures.containsKey(taskId);
  }

  /** 取 Profile 对应的执行互斥锁(不存在则建). 包私有:harness 用它模拟同 Profile 占用. */
  Lock profileLockFor(String profileName) {
    return profileLocks.computeIfAbsent(profileName, name -> new ReentrantLock());
  }

  private Lock admissionLockFor(String taskId) {
    return admissionLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  private static Instant nextCandidate(CronTrigger trigger) {
    // TriggerContext 的 lastCompletion 决定下一次候选起点;三个时刻都给"现在"即取下一个未来触发点
    Instant now = Instant.now();
    return trigger.nextExecution(
        new org.springframework.scheduling.support.SimpleTriggerContext(now, now, now));
  }

  /** 测试专用:覆盖单次执行截止(稳定性夹具用短截止,生产保持 60 秒). */
  void overrideExecutionTimeoutMs(long timeoutMs) {
    this.executionTimeoutMs = timeoutMs;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Catalog 条目:当前合法规则的定义与触发器快照. */
  private record CatalogEntry(Profile profile, ScheduleConfig schedule, CronTrigger trigger) {}

  /** 一次执行的私有结果载体:worker 与 watchdog 竞争写入,等待方经 latch 汇合. */
  private static final class ExecutionHandle {

    private final String taskId;

    private final long startedMillis = System.currentTimeMillis();

    private final CountDownLatch latch = new CountDownLatch(1);

    private volatile Thread workerThread;

    private volatile TaskExecutionView execution;

    private volatile TaskExecutionView finishedView;

    private volatile ScheduledFuture<?> watchdogTask;

    private volatile RejectedExecutionException rejected;

    private volatile RuntimeException failure;

    private volatile RuntimeException infraFailure;

    private ExecutionHandle(String taskId) {
      this.taskId = taskId;
    }

    private void reject(RejectedExecutionException exception) {
      this.rejected = exception;
      latch.countDown();
    }

    private void fail(RuntimeException exception) {
      this.failure = exception;
      latch.countDown();
    }
  }

  /** 分类结果:成功或带固定错误分类的失败. */
  private record Outcome(boolean success, String errorMessage) {

    private static Outcome successOutcome() {
      return new Outcome(true, null);
    }

    private static Outcome failureOutcome(String errorMessage) {
      return new Outcome(false, errorMessage);
    }
  }
}
