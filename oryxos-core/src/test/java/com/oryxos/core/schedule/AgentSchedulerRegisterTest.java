package com.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.oryxos.core.session.SessionManager;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** 单 Profile 定时注册 harness(29 节):registerProfile 后句柄表有句柄,cron/时区/message 逐字来自声明;非法规则跳过不阻断. */
class AgentSchedulerRegisterTest {

  private static final ScheduleConfig MORNING =
      new ScheduleConfig("reconcile-morning", "0 0 9 * * *", "Asia/Shanghai", "到点了，核对昨天的订单对账。");

  private static final ScheduleConfig EVENING =
      new ScheduleConfig("reconcile-evening", "0 30 18 * * *", "UTC", "晚间复核");

  private ThreadPoolTaskScheduler taskScheduler;
  private ScheduledTaskStore store;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(ThreadPoolTaskScheduler.class);
    store = mock(ScheduledTaskStore.class);
    when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
        .thenReturn(mock(ScheduledFuture.class));
    when(store.register(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new ScheduledTaskView(
                    ((ScheduleConfig) invocation.getArgument(1)).id(),
                    invocation.getArgument(0),
                    ((ScheduleConfig) invocation.getArgument(1)).cron(),
                    ((ScheduleConfig) invocation.getArgument(1)).zone(),
                    ((ScheduleConfig) invocation.getArgument(1)).message(),
                    true,
                    invocation.getArgument(2),
                    null,
                    null,
                    0));
    scheduler =
        new AgentScheduler(
            taskScheduler,
            Runnable::run,
            new ProfileRegistry(List.of()),
            mock(AgentService.class),
            mock(SessionManager.class),
            store);
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

  @Test
  @DisplayName("registerProfile后句柄表有句柄且isRegistered为真")
  void registerProfile_installsHandlePerTask() {
    scheduler.registerProfile(profileWith("daily-reconcile", MORNING, EVENING));

    assertThat(scheduler.isRegistered("reconcile-morning")).isTrue();
    assertThat(scheduler.isRegistered("reconcile-evening")).isTrue();
    assertThat(scheduler.hasCronHandle("reconcile-morning")).isTrue();
    assertThat(scheduler.hasCronHandle("reconcile-evening")).isTrue();
  }

  @Test
  @DisplayName("cron/时区/message逐字来自Profile.schedules声明")
  void registerProfile_usesDeclaredCronZoneAndMessage() {
    scheduler.registerProfile(profileWith("daily-reconcile", MORNING));

    ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), triggerCaptor.capture());
    // CronTrigger 无时区 getter,equals 同时覆盖表达式与时区——比逐字段断言更强
    assertThat(triggerCaptor.getValue())
        .isEqualTo(new CronTrigger("0 0 9 * * *", ZoneId.of("Asia/Shanghai")));

    ArgumentCaptor<ScheduleConfig> scheduleCaptor = ArgumentCaptor.forClass(ScheduleConfig.class);
    verify(store).register(any(), scheduleCaptor.capture(), any());
    assertThat(scheduleCaptor.getValue().cron()).isEqualTo("0 0 9 * * *");
    assertThat(scheduleCaptor.getValue().zone()).isEqualTo("Asia/Shanghai");
    assertThat(scheduleCaptor.getValue().message()).isEqualTo("到点了，核对昨天的订单对账。");
  }

  @Test
  @DisplayName("非法规则跳过不阻断同Profile其余规则")
  void registerProfile_skipsInvalidRuleButKeepsValid() {
    ScheduleConfig badCron = new ScheduleConfig("bad-rule", "not-a-cron", "Asia/Shanghai", "坏规则");

    scheduler.registerProfile(profileWith("daily-reconcile", badCron, MORNING));

    assertThat(scheduler.isRegistered("bad-rule")).isFalse();
    assertThat(scheduler.hasCronHandle("bad-rule")).isFalse();
    assertThat(scheduler.isRegistered("reconcile-morning")).isTrue();
    assertThat(scheduler.hasCronHandle("reconcile-morning")).isTrue();
  }

  @Test
  @DisplayName("重复id后者跳过且与catalog既有任务撞车也跳过")
  void registerProfile_skipsDuplicateIds() {
    scheduler.registerProfile(profileWith("agent-a", MORNING));
    // 不同 Profile 复用同一规则 id:与既有 catalog 撞车,必须跳过而非互相覆盖
    ScheduleConfig sameId =
        new ScheduleConfig("reconcile-morning", "0 30 10 * * *", "UTC", "另一个同名 id");
    scheduler.registerProfile(profileWith("agent-b", sameId));

    verify(store, times(1)).register(any(), any(), any());
  }

  @Test
  @DisplayName("无schedules的Profile注册为空操作")
  void registerProfile_noSchedulesIsNoop() {
    scheduler.registerProfile(profileWith("plain-agent"));

    verify(store, never()).register(any(), any(), any());
    assertThat(scheduler.hasCronHandle("anything")).isFalse();
  }
}
