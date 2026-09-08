package com.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** 定时调度器 harness:009 的注册/时区/重叠/放锁/身份守点,加 011 的登记入 Store、catalog、启停、runNow 准入互斥. */
class AgentSchedulerTest {

  private static final ScheduleConfig MORNING_REPORT =
      new ScheduleConfig("morning-report", "0 0 9 * * *", "Asia/Shanghai", "生成昨日运维日报");

  private ThreadPoolTaskScheduler taskScheduler;
  private AgentService agentService;
  private SessionManager sessionManager;
  private ScheduledTaskStore store;

  private Thread caller;

  private AgentScheduler schedulerUnderTest;
  private java.util.concurrent.atomic.AtomicLong executionIds;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(ThreadPoolTaskScheduler.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    store = mock(ScheduledTaskStore.class);
    executionIds = new java.util.concurrent.atomic.AtomicLong(1);
    when(store.register(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskView(
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).id(),
                    invocation.getArgument(0),
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).cron(),
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).zone(),
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).message(),
                    true,
                    invocation.getArgument(2),
                    null,
                    null,
                    0));
    when(store.begin(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new TaskExecutionView(
                    executionIds.getAndIncrement(),
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    null,
                    null,
                    null));
    when(store.finish(anyLong(), anyBoolean(), any(), any()))
        .thenAnswer(
            invocation ->
                new TaskExecutionView(
                    invocation.getArgument(0),
                    "task-1",
                    "sid",
                    java.time.Instant.now(),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
  }

  @Test
  @DisplayName("注册时CronTrigger带上了配置的cron和时区")
  void registrationCarriesConfiguredCronAndZone() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();

    ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), triggerCaptor.capture());
    // CronTrigger 无时区 getter,equals 同时覆盖表达式与时区——比逐字段断言更强
    assertThat(triggerCaptor.getValue())
        .isEqualTo(new CronTrigger("0 0 9 * * *", ZoneId.of("Asia/Shanghai")));
  }

  @Test
  @DisplayName("到点触发走与人推相同的处理入口")
  void runOnceDispatchesMessageThroughUnifiedEntry() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    Session session = new Session("sid", "ops");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "ops")).thenReturn(session);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.runOnce(profile, MORNING_REPORT);

    verify(agentService).process(session, "生成昨日运维日报");
  }

  @Test
  @DisplayName("cron非法的规则跳过注册且不阻断启动")
  void invalidCronIsSkippedWithoutBlocking() {
    ScheduleConfig bad = new ScheduleConfig("bad-cron", "not-a-cron", "Asia/Shanghai", "日报");
    Profile profile = profileWith("ops", bad, MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();

    // 非法条跳过、合法条照常注册——全表只注册一次
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("四要素缺失的规则跳过注册")
  void missingElementIsSkipped() {
    ScheduleConfig noMessage = new ScheduleConfig("t-no-msg", "0 0 9 * * *", "Asia/Shanghai", " ");
    ScheduleConfig noId = new ScheduleConfig(" ", "0 0 9 * * *", "Asia/Shanghai", "日报");
    ScheduleConfig noZone = new ScheduleConfig("t-no-zone", "0 0 9 * * *", null, "日报");
    Profile profile = profileWith("ops", noMessage, noId, noZone);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();

    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("规则id全实例唯一_重复者跳过注册")
  void duplicateIdIsSkipped() {
    ScheduleConfig dup = new ScheduleConfig("morning-report", "0 30 8 * * *", "UTC", "重复 id");
    Profile ops = profileWith("ops", MORNING_REPORT);
    Profile sales = profileWith("sales", dup);
    AgentScheduler scheduler = schedulerFor(registryOf(ops, sales));

    scheduler.registerAll();

    // 跨 Profile 撞 id 也只保留先注册的一条——锁按裸 id 持有,撞 id 会导致不同任务互相阻塞
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("Profile未声明规则时零注册不报错")
  void emptySchedulesRegistersNothing() {
    AgentScheduler scheduler = schedulerFor(registryOf(profileWith("ops")));

    scheduler.registerAll();

    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("多个Profile的规则全部注册互不影响")
  void multipleProfilesAllRegistered() {
    Profile ops = profileWith("ops", MORNING_REPORT);
    Profile sales =
        profileWith("sales", new ScheduleConfig("weekly", "0 0 18 * * FRI", "UTC", "周报"));
    AgentScheduler scheduler = schedulerFor(registryOf(ops, sales));

    scheduler.registerAll();

    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("上一次还没跑完_本次触发直接跳过")
  void skipsTriggerWhenPreviousRunStillHoldsLock() throws Exception {
    ScheduleConfig task = new ScheduleConfig("task-1", "0 0 9 * * *", "Asia/Shanghai", "日报");
    Profile profile = profileWith("ops", task);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    Lock lock = scheduler.lockFor("task-1");
    // 课件骨架在单线程里 lock() 后调 runOnce,但 ReentrantLock 可重入——同线程 tryLock 必成功,
    // 测不到跳过语义。真实重叠发生在调度池另一线程,故由持锁线程占用锁、测试线程触发。
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              locked.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();
    locked.await();
    try {
      scheduler.runOnce(profile, task);
      verify(agentService, never()).process(any(), any()); // 没有叠加执行
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  @DisplayName("任务抛异常_不外抛且锁必须被释放")
  void exceptionDoesNotEscapeAndLockIsReleased() {
    ScheduleConfig task = new ScheduleConfig("task-1", "0 0 9 * * *", "Asia/Shanghai", "日报");
    Profile profile = profileWith("ops", task);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(new Session("sid", "ops"));
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    assertDoesNotThrow(() -> scheduler.runOnce(profile, task));

    scheduler.runOnce(profile, task); // 二进宫:能进来才证明锁真的放了,没有永久卡死
    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  @DisplayName("两次定时触发拿到同一Session")
  void repeatedTriggersReuseSameSession() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    Session session = new Session("sid", "ops");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "ops")).thenReturn(session);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.runOnce(profile, MORNING_REPORT);
    scheduler.runOnce(profile, MORNING_REPORT);

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", "ops");
    verify(agentService, times(2)).process(session, "生成昨日运维日报");
  }

  @Test
  @DisplayName("会话三元组逐字固定为scheduler_scheduler_profile名")
  void sessionIdentityTripleIsVerbatimScheduler() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(new Session("sid", "ops"));
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.runOnce(profile, MORNING_REPORT);

    ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> profileName = ArgumentCaptor.forClass(String.class);
    verify(sessionManager).getOrCreate(channel.capture(), user.capture(), profileName.capture());
    assertThat(channel.getValue()).isEqualTo("scheduler");
    assertThat(user.getValue()).isEqualTo("scheduler");
    assertThat(profileName.getValue()).isEqualTo("ops");
  }

  @Test
  @DisplayName("登记时规则与候选时间写入Store")
  void registrationPassesRuleAndCandidateIntoStore() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();

    verify(store)
        .register(
            org.mockito.ArgumentMatchers.eq("ops"),
            org.mockito.ArgumentMatchers.eq(MORNING_REPORT),
            org.mockito.ArgumentMatchers.any(Instant.class));
    assertThat(scheduler.isRegistered("morning-report")).isTrue();
    assertThat(scheduler.isRegistered("ghost")).isFalse();
  }

  @Test
  @DisplayName("非法规则不进Store也不进catalog")
  void invalidRuleStaysOutOfStoreAndCatalog() {
    ScheduleConfig bad = new ScheduleConfig("bad-cron", "not-a-cron", "Asia/Shanghai", "日报");
    Profile profile = profileWith("ops", bad, MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();

    verify(store, never())
        .register(
            org.mockito.ArgumentMatchers.eq("ops"), org.mockito.ArgumentMatchers.eq(bad), any());
    assertThat(scheduler.isRegistered("bad-cron")).isFalse();
    assertThat(scheduler.isRegistered("morning-report")).isTrue();
  }

  @Test
  @DisplayName("重复registerAll_旧安装被取消且不重复注册")
  void repeatedRegisterAllCancelsOldInstallations() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    ScheduledFuture<?> firstInstall = mock(ScheduledFuture.class);
    ScheduledFuture<?> secondInstall = mock(ScheduledFuture.class);
    org.mockito.Mockito.doReturn(firstInstall, secondInstall)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Trigger.class));
    AgentScheduler scheduler = schedulerFor(registryOf(profile));

    scheduler.registerAll();
    scheduler.registerAll();

    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
    verify(firstInstall).cancel(false);
    // 每次注册都重新登记定义(保留 enabled 的语义在 Store 层)
    verify(store, times(2)).register(any(), any(), any());
  }

  @Test
  @DisplayName("停用任务的自动触发零执行零历史")
  void automaticSkipsDisabledTaskZeroHistory() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    scheduler.registerAll();
    when(store.findTask("morning-report"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    false,
                    null,
                    null,
                    null,
                    0)));

    ArgumentCaptor<Runnable> cronCallback = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(cronCallback.capture(), any(Trigger.class));
    cronCallback.getValue().run();

    verify(store, never()).begin(any(), any(), any());
    verify(agentService, never()).process(any(), any());
    verify(store, never()).updateNextRun(any(), any());
  }

  @Test
  @DisplayName("停用任务仍可手动runNow执行")
  void disabledTaskStillAllowsManualRunNow() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    Session session = new Session("sid", "ops");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "ops")).thenReturn(session);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    scheduler.registerAll();
    when(store.findTask("morning-report"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    false,
                    null,
                    null,
                    null,
                    0)));

    TaskExecutionView view = scheduler.runNow("morning-report");

    verify(store)
        .begin(
            org.mockito.ArgumentMatchers.eq("morning-report"),
            org.mockito.ArgumentMatchers.eq("sid"),
            any());
    verify(agentService).process(session, "生成昨日运维日报");
    assertThat(view).isNotNull();
  }

  @Test
  @DisplayName("runNow未知任务404语义_规则失效400语义")
  void runNowUnknownAndStaleRulesRejected() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    scheduler.registerAll();

    assertThatThrownBy(() -> scheduler.runNow("ghost"))
        .isInstanceOf(java.util.NoSuchElementException.class);
    when(store.findTask("stale"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledTaskView(
                    "stale",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "旧规则",
                    true,
                    null,
                    null,
                    null,
                    0)));
    assertThatThrownBy(() -> scheduler.runNow("stale"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("规则失效");
  }

  @Test
  @DisplayName("runNow同任务忙碌_拒绝且零新增历史")
  void runNowBusyTaskRejectedWithoutHistory() throws Exception {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    scheduler.registerAll();
    when(store.findTask("morning-report"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    true,
                    null,
                    null,
                    null,
                    0)));
    CountDownLatch release = holdLockInAnotherThread(scheduler.lockFor("morning-report"));

    try {
      assertThatThrownBy(() -> scheduler.runNow("morning-report"))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      release.countDown();
    }
    verify(store, never()).begin(any(), any(), any());
  }

  @Test
  @DisplayName("runNow同Profile忙碌_拒绝;不同Profile可执行")
  void runNowSameProfileBusyRejectedDifferentProfileAllowed() throws Exception {
    ScheduleConfig taskA = new ScheduleConfig("task-a", "0 0 9 * * *", "Asia/Shanghai", "A");
    ScheduleConfig taskB = new ScheduleConfig("task-b", "0 0 10 * * *", "Asia/Shanghai", "B");
    Profile ops = profileWith("ops", taskA);
    Profile sales = profileWith("sales", taskB);
    AgentScheduler scheduler = schedulerFor(registryOf(ops, sales));
    scheduler.registerAll();
    when(store.findTask("task-a")).thenReturn(java.util.Optional.of(viewOf("task-a", "ops")));
    when(store.findTask("task-b")).thenReturn(java.util.Optional.of(viewOf("task-b", "sales")));
    when(sessionManager.getOrCreate("scheduler", "scheduler", "sales"))
        .thenReturn(new Session("sid-b", "sales"));
    CountDownLatch release = holdLockInAnotherThread(scheduler.profileLockFor("ops"));

    try {
      assertThatThrownBy(() -> scheduler.runNow("task-a"))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("同 Profile");
      // 不同 Profile 不受 ops 占用影响
      assertThat(scheduler.runNow("task-b")).isNotNull();
      verify(agentService).process(any(), org.mockito.ArgumentMatchers.eq("B"));
    } finally {
      release.countDown();
    }
    verify(store, never()).begin(org.mockito.ArgumentMatchers.eq("task-a"), any(), any());
  }

  @Test
  @DisplayName("执行中停用_立即返回不等worker结束")
  void setEnabledDuringExecutionReturnsImmediately() {
    Profile profile = profileWith("ops", MORNING_REPORT);
    AgentScheduler scheduler = schedulerFor(registryOf(profile));
    scheduler.registerAll();
    when(store.findTask("morning-report"))
        .thenReturn(
            java.util.Optional.of(
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    true,
                    null,
                    null,
                    null,
                    0)));
    when(store.setEnabled(
            org.mockito.ArgumentMatchers.eq("morning-report"),
            org.mockito.ArgumentMatchers.eq(false),
            any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    false,
                    null,
                    null,
                    null,
                    0));

    ScheduledTaskView view = scheduler.setEnabled("morning-report", false);

    assertThat(view.enabled()).isFalse();
    assertThat(view.nextRunAt()).isNull();
  }

  // ─── T014: 结果分类、截止与竞态 ───

  @Test
  @DisplayName("普通答复掩盖工具失败_仍按工具执行失败记账")
  void plainReplyAfterToolFailure_classifiedAsToolFailure() {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(assistantCalling("http_get"));
              session.appendToolResult(toolResponse("http_get", false));
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("已为您处理"));
              return "已为您处理";
            })
        .when(agentService)
        .process(any(), any());

    scheduler.runNow("morning-report");

    verify(store)
        .finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("工具执行失败"),
            any());
  }

  @Test
  @DisplayName("工具结果缺元数据_按失败而非猜测")
  void missingMetadata_classifiedAsFailure() {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(assistantCalling("http_get"));
              session.appendToolResult(
                  org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                      .responses(
                          List.of(
                              new org.springframework.ai.chat.messages.ToolResponseMessage
                                  .ToolResponse("c-1", "http_get", "sunny")))
                      .build());
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("收尾"));
              return "收尾";
            })
        .when(agentService)
        .process(any(), any());

    scheduler.runNow("morning-report");

    verify(store)
        .finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("工具执行失败"),
            any());
  }

  @Test
  @DisplayName("尾消息是ToolResponse_判轮数耗尽")
  void toolResponseTail_classifiedAsIterationsExhausted() {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(assistantCalling("http_get"));
              session.appendToolResult(toolResponse("http_get", true));
              return "达到最大轮数,已停止";
            })
        .when(agentService)
        .process(any(), any());

    scheduler.runNow("morning-report");

    verify(store)
        .finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("轮数耗尽"),
            any());
  }

  @Test
  @DisplayName("模型调用异常_按模型调用失败记账且锁释放")
  void engineException_classifiedAsLlmFailure() {
    drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("connect timeout"));

    scheduler.runNow("morning-report");

    verify(store)
        .finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("模型调用失败"),
            any());
    // 锁已释放:紧接着的下一次仍能进入执行(重打桩用 doReturn——when() 会先执行旧的 throw 答案)
    org.mockito.Mockito.doReturn("恢复").when(agentService).process(any(), any());
    scheduler.runNow("morning-report");
  }

  @Test
  @DisplayName("begin存储失败_引擎不启动且错误原样上抛")
  void beginFailure_engineNeverStartsAndErrorPropagates() {
    drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    when(store.begin(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> scheduler.runNow("morning-report"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("db down");
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("finish存储失败_绝不伪报成功")
  void finishFailure_neverReportedAsSuccess() {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("完成"));
              return "完成";
            })
        .when(agentService)
        .process(any(), any());
    when(store.finish(anyLong(), anyBoolean(), any(), any()))
        .thenThrow(new IllegalStateException("write blocked"));

    assertThatThrownBy(() -> scheduler.runNow("morning-report"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("终态记账失败");
    verify(agentService).process(any(), any());
  }

  @Test
  @DisplayName("正常完成后watchdog不再写终态_终态唯一")
  void completedExecution_watchdogStaysSilent() {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = registeredScheduler("ops", MORNING_REPORT);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("完成"));
              return "完成";
            })
        .when(agentService)
        .process(any(), any());

    scheduler.runNow("morning-report");

    Runnable watchdog = capturedWatchdog();
    watchdog.run();
    // 只有 worker 的一次终态写入,watchdog 静默
    verify(store, times(1)).finish(anyLong(), anyBoolean(), any(), any());
  }

  @Test
  @DisplayName("超时先到_迟到返回不覆盖timeout")
  void timeoutWins_lateCompletionDoesNotOverwrite() throws Exception {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = asyncRegisteredScheduler("ops", MORNING_REPORT);
    CountDownLatch processStarted = new CountDownLatch(1);
    CountDownLatch processRelease = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              processStarted.countDown();
              processRelease.await();
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("迟到"));
              return "迟到";
            })
        .when(agentService)
        .process(any(), any());
    when(store.finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("执行超时"),
            any()))
        .thenAnswer(
            invocation ->
                new TaskExecutionView(
                    invocation.getArgument(0),
                    "morning-report",
                    "sid",
                    Instant.now(),
                    false,
                    "执行超时",
                    60000L));

    java.util.concurrent.atomic.AtomicReference<TaskExecutionView> outcome =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread caller = runInThread(() -> outcome.set(scheduler.runNow("morning-report")));
    assertThat(processStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    // 到点:watchdog 中断 worker 并写超时终态
    capturedWatchdog().run();
    caller.join(java.time.Duration.ofSeconds(5).toMillis());
    assertThat(outcome.get()).isNotNull();
    assertThat(outcome.get().errorMessage()).isEqualTo("执行超时");
    // 迟到的 worker 收尾不能再改写(Store 条件终态语义由 mock 复现:第二次 finish 仍回 timeout 视图)
    processRelease.countDown();
  }

  @Test
  @DisplayName("worker不响应中断_锁保留到真实退出")
  void unresponsiveWorker_lockHeldUntilRealExit() throws Exception {
    drivenSession("ops");
    AgentScheduler scheduler = asyncRegisteredScheduler("ops", MORNING_REPORT);
    CountDownLatch processStarted = new CountDownLatch(1);
    CountDownLatch processRelease = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              processStarted.countDown();
              // 不响应中断:一直等到显式放行
              boolean released = false;
              while (!released) {
                try {
                  released = processRelease.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  // 刻意吞掉中断(swallow interrupts),模拟失控工具
                }
              }
              return "very late";
            })
        .when(agentService)
        .process(any(), any());

    caller = runInThread(() -> scheduler.runNow("morning-report"));
    assertThat(processStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    capturedWatchdog().run();
    java.util.concurrent.locks.ReentrantLock taskLock =
        (java.util.concurrent.locks.ReentrantLock) scheduler.lockFor("morning-report");
    assertThat(taskLock.isLocked()).isTrue();

    processRelease.countDown();
    caller.join(java.time.Duration.ofSeconds(5).toMillis());
    // 等 worker 真实退出:调用方 join 只等 runNow 返回,锁由 worker 线程释放
    long deadline = System.currentTimeMillis() + 5000;
    while (taskLock.isLocked() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    org.assertj.core.api.Assertions.assertThat(taskLock.isLocked()).isFalse();
  }

  @Test
  @DisplayName("执行中停用_立即返回不等worker结束")
  void disableDuringExecution_returnsImmediately() throws Exception {
    drivenSession("ops");
    AgentScheduler scheduler = asyncRegisteredScheduler("ops", MORNING_REPORT);
    CountDownLatch processStarted = new CountDownLatch(1);
    CountDownLatch processRelease = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              processStarted.countDown();
              processRelease.await();
              return "done";
            })
        .when(agentService)
        .process(any(), any());
    when(store.setEnabled(
            org.mockito.ArgumentMatchers.eq("morning-report"),
            org.mockito.ArgumentMatchers.eq(false),
            any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskView(
                    "morning-report",
                    "ops",
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "生成昨日运维日报",
                    false,
                    null,
                    null,
                    null,
                    0));

    caller = runInThread(() -> scheduler.runNow("morning-report"));
    assertThat(processStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    ScheduledTaskView view = scheduler.setEnabled("morning-report", false);
    assertThat(view.enabled()).isFalse();

    processRelease.countDown();
    caller.join(java.time.Duration.ofSeconds(5).toMillis());
  }

  @Test
  @DisplayName("等待方被中断_已从Store读到timeout终态则返回该视图而非基础设施错误")
  void callerInterrupted_returnsTimeoutTerminalFromStore() throws Exception {
    drivenSession("ops");
    schedulerUnderTest = asyncRegisteredScheduler("ops", MORNING_REPORT);
    CountDownLatch processStarted = new CountDownLatch(1);
    CountDownLatch processRelease = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              processStarted.countDown();
              processRelease.await();
              return "very late";
            })
        .when(agentService)
        .process(any(), any());
    TaskExecutionView timeoutView =
        new TaskExecutionView(9L, "morning-report", "sid", Instant.now(), false, "执行超时", 60000L);
    org.mockito.Mockito.doReturn(timeoutView)
        .when(store)
        .finish(
            anyLong(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("执行超时"),
            any());
    // begin 与 timeout 终态必须是同一个 executionId,否则中断回读按 id 对不上
    org.mockito.Mockito.doReturn(
            new TaskExecutionView(9L, "morning-report", "sid", Instant.now(), null, null, null))
        .when(store)
        .begin(any(), any(), any());
    when(store.listExecutions(
            org.mockito.ArgumentMatchers.eq("morning-report"),
            org.mockito.ArgumentMatchers.eq(0),
            org.mockito.ArgumentMatchers.eq(1)))
        .thenReturn(List.of(timeoutView));

    java.util.concurrent.atomic.AtomicReference<Object> outcome =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    caller =
        runInThread(
            () -> {
              try {
                outcome.set(schedulerUnderTest.runNow("morning-report"));
              } catch (RuntimeException e) {
                failure.set(e);
              }
            });
    assertThat(processStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    capturedWatchdog().run();
    // 模拟 MVC 异步超时掐断请求线程:打断正在等待的调用方
    caller.interrupt();
    caller.join(java.time.Duration.ofSeconds(5).toMillis());

    assertThat(failure.get()).isNull();
    assertThat(outcome.get()).isInstanceOf(TaskExecutionView.class);
    assertThat(((TaskExecutionView) outcome.get()).errorMessage()).isEqualTo("执行超时");

    processRelease.countDown();
  }

  @Test
  @DisplayName("存储阻塞时_等待仍有本地截止")
  void blockedStore_waitStillHasLocalDeadline() throws Exception {
    Session session = drivenSession("ops");
    AgentScheduler scheduler = asyncRegisteredScheduler("ops", MORNING_REPORT);
    scheduler.overrideExecutionTimeoutMs(200L);
    CountDownLatch finishBlock = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              session.append(new org.springframework.ai.chat.messages.AssistantMessage("完成"));
              return "完成";
            })
        .when(agentService)
        .process(any(), any());
    when(store.finish(anyLong(), anyBoolean(), any(), any()))
        .thenAnswer(
            invocation -> {
              // 模拟持久化卡死:两端点(worker 与 watchdog)都会堵在这里
              finishBlock.await();
              return new TaskExecutionView(
                  invocation.getArgument(0),
                  "morning-report",
                  "sid",
                  Instant.now(),
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  invocation.getArgument(3));
            });

    try {
      assertThatThrownBy(() -> scheduler.runNow("morning-report"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("超过本地截止");
    } finally {
      finishBlock.countDown();
    }
  }

  private Session drivenSession(String profileName) {
    Session session = new Session("sid-" + profileName, profileName);
    when(sessionManager.getOrCreate("scheduler", "scheduler", profileName)).thenReturn(session);
    return session;
  }

  private AgentScheduler registeredScheduler(String profileName, ScheduleConfig schedule) {
    AgentScheduler scheduler = schedulerFor(registryOf(profileWith(profileName, schedule)));
    scheduler.registerAll();
    when(store.findTask(schedule.id()))
        .thenReturn(java.util.Optional.of(viewOf(schedule.id(), profileName)));
    return scheduler;
  }

  private AgentScheduler asyncRegisteredScheduler(String profileName, ScheduleConfig schedule) {
    AgentScheduler scheduler =
        new AgentScheduler(
            taskScheduler,
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            registryOf(profileWith(profileName, schedule)),
            agentService,
            sessionManager,
            store);
    scheduler.registerAll();
    when(store.findTask(schedule.id()))
        .thenReturn(java.util.Optional.of(viewOf(schedule.id(), profileName)));
    return scheduler;
  }

  /** 抓取最近一次以 Instant 方式安装的 watchdog 回调(注册 cron 走 Trigger 重载,天然分流). */
  private Runnable capturedWatchdog() {
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler, org.mockito.Mockito.atLeastOnce())
        .schedule(runnableCaptor.capture(), any(Instant.class));
    return runnableCaptor.getValue();
  }

  private static Thread runInThread(Runnable action) {
    Thread thread = new Thread(action);
    thread.start();
    return thread;
  }

  private static org.springframework.ai.chat.messages.AssistantMessage assistantCalling(
      String tool) {
    return org.springframework.ai.chat.messages.AssistantMessage.builder()
        .content("")
        .toolCalls(
            List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "c-1", "function", tool, "{}")))
        .build();
  }

  private static org.springframework.ai.chat.messages.ToolResponseMessage toolResponse(
      String tool, boolean success) {
    return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
        .responses(
            List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                    "c-1", tool, success ? "sunny" : "ERROR: boom")))
        .metadata(java.util.Map.of("oryxos.tool.success", success))
        .build();
  }

  private static ScheduledTaskView viewOf(String taskId, String profileName) {
    return new ScheduledTaskView(
        taskId, profileName, "0 0 9 * * *", "Asia/Shanghai", "任务", true, null, null, null, 0);
  }

  /** 在另一线程持锁直到返回的 release 闩被触发(解锁与持锁同线程,符合 ReentrantLock 线程亲和). */
  private static CountDownLatch holdLockInAnotherThread(Lock lock) {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              locked.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();
    try {
      locked.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return release;
  }

  private AgentScheduler schedulerFor(ProfileRegistry registry) {
    // 直连执行器:harness 内 worker 同步内联,断言不必等异步
    return new AgentScheduler(
        taskScheduler, Runnable::run, registry, agentService, sessionManager, store);
  }

  private static ProfileRegistry registryOf(Profile... profiles) {
    return new ProfileRegistry(List.of(profiles));
  }

  private static Profile profileWith(String name, ScheduleConfig... schedules) {
    return new Profile(
        name,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(schedules),
        null,
        null,
        null,
        null);
  }
}
