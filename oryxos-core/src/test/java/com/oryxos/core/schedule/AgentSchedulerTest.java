package com.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** 第 25 节验收 harness:一个类覆盖注册时区、重叠跳过、失败放锁、会话身份四个坑,外加 FR-008 非法规则处置. */
class AgentSchedulerTest {

  private static final ScheduleConfig MORNING_REPORT =
      new ScheduleConfig("morning-report", "0 0 9 * * *", "Asia/Shanghai", "生成昨日运维日报");

  private ThreadPoolTaskScheduler taskScheduler;
  private AgentService agentService;
  private SessionManager sessionManager;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(ThreadPoolTaskScheduler.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
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

  private AgentScheduler schedulerFor(ProfileRegistry registry) {
    return new AgentScheduler(taskScheduler, registry, agentService, sessionManager);
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
