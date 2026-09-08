package com.oryxos.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.tool.OryxTool;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("核心引擎Memory端口装配")
class CoreEngineConfigurationTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("存在MemoryService时注入真实端口且toolTable仍精确限定")
  void injectsMemoryPortWithoutDependingOnImplementation() throws Exception {
    Method factory = memoryAwareFactoryMethod();
    MemoryService memory = mock(MemoryService.class);
    ObjectProvider<MemoryService> memories = provider(memory);
    ObjectProvider<Map<String, OryxTool>> tools = provider(Map.of());

    PromptBuilder builder =
        (PromptBuilder)
            factory.invoke(
                new CoreEngineConfiguration(), new ContextLoader(workspace), memories, tools);

    assertThat(ReflectionTestUtils.getField(builder, "memoryService")).isSameAs(memory);
    Qualifier qualifier = factory.getParameters()[2].getAnnotation(Qualifier.class);
    assertThat(qualifier).isNotNull();
    assertThat(qualifier.value()).isEqualTo("toolTable");
    assertThrows(
        ClassNotFoundException.class,
        () ->
            Class.forName(
                "com.oryxos.memory.MemoryServiceImpl",
                false,
                CoreEngineConfiguration.class.getClassLoader()));
  }

  @Test
  @DisplayName("Memory实现缺席时保持二参构造的历史行为")
  void fallsBackToCompatibleEmptyMemoryPort() throws Exception {
    Method factory = memoryAwareFactoryMethod();
    ObjectProvider<MemoryService> memories = provider(null);
    ObjectProvider<Map<String, OryxTool>> tools = provider(Map.of());
    PromptBuilder builder =
        (PromptBuilder)
            factory.invoke(
                new CoreEngineConfiguration(), new ContextLoader(workspace), memories, tools);
    Session session = new Session("s", "p");
    session.append(new UserMessage("保留历史"));

    assertThat(builder.build(session, profile()).messages().getLast().getText()).isEqualTo("保留历史");
  }

  private static Method memoryAwareFactoryMethod() {
    return Arrays.stream(CoreEngineConfiguration.class.getMethods())
        .filter(method -> "promptBuilder".equals(method.getName()))
        .filter(method -> method.getParameterCount() == 3)
        .findFirst()
        .orElseThrow(() -> new AssertionError("PromptBuilder装配必须接入MemoryService端口"));
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    when(provider.getIfAvailable(any(Supplier.class)))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<T> fallback = invocation.getArgument(0);
              return value == null ? fallback.get() : value;
            });
    return provider;
  }

  @Test
  @DisplayName("调度器工厂装配后_初始化回调恢复并注册全部定时规则")
  void registersSchedulesOnFactoryCall() {
    ThreadPoolTaskScheduler taskScheduler = mock(ThreadPoolTaskScheduler.class);
    ProfileRegistry registry = new ProfileRegistry(List.of(profileWithSchedule()));
    ScheduledTaskStore store = mock(ScheduledTaskStore.class);
    when(store.register(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new com.oryxos.core.schedule.ScheduledTaskView(
                    ((com.oryxos.core.profile.ScheduleConfig) invocation.getArgument(1)).id(),
                    invocation.getArgument(0),
                    "0 0 9 * * *",
                    "Asia/Shanghai",
                    "日报",
                    true,
                    invocation.getArgument(2),
                    null,
                    null,
                    0));
    CoreEngineConfiguration configuration = new CoreEngineConfiguration();
    AgentScheduler scheduler =
        configuration.agentScheduler(
            taskScheduler,
            new org.springframework.core.task.SimpleAsyncTaskExecutor(),
            registry,
            mock(AgentService.class),
            mock(SessionManager.class),
            store);

    // 注册动作已挪到上下文初始化完成后的回调(先恢复遗留 running 再安装 cron)
    configuration.schedulerRegistrar(scheduler).afterSingletonsInstantiated();

    verify(store).recoverInterrupted();
    verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("调度器Bean按启用信号条件装配_缺省不生成(chat等交互命令注册数为零)")
  void agentSchedulerBeanIsConditionalOnResidentModeFlag() throws Exception {
    Method factory =
        CoreEngineConfiguration.class.getMethod(
            "agentScheduler",
            ThreadPoolTaskScheduler.class,
            org.springframework.core.task.SimpleAsyncTaskExecutor.class,
            ProfileRegistry.class,
            AgentService.class,
            SessionManager.class,
            ScheduledTaskStore.class);

    ConditionalOnProperty condition = factory.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("oryxos.scheduler");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
  }

  @Test
  @DisplayName("调度器执行器Bean_Spring托管虚拟线程且有界关闭取消")
  void schedulerWorkerExecutorIsVirtualAndBoundedOnClose() throws Exception {
    Method factory = CoreEngineConfiguration.class.getDeclaredMethod("schedulerWorkerExecutor");

    Bean beanAnnotation = factory.getAnnotation(Bean.class);
    assertThat(beanAnnotation.destroyMethod()).isEqualTo("close");

    try (org.springframework.core.task.SimpleAsyncTaskExecutor executor =
        (org.springframework.core.task.SimpleAsyncTaskExecutor)
            factory.invoke(new CoreEngineConfiguration())) {
      assertThat(executor).hasFieldOrPropertyWithValue("taskTerminationTimeout", 5000L);
      assertThat(executor).hasFieldOrPropertyWithValue("cancelRemainingTasksOnClose", true);
      // 行为断言:执行器跑出的线程确实是虚拟线程
      java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.atomic.AtomicBoolean wasVirtual =
          new java.util.concurrent.atomic.AtomicBoolean(false);
      executor.execute(
          () -> {
            wasVirtual.set(Thread.currentThread().isVirtual());
            ran.countDown();
          });
      assertThat(ran.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(wasVirtual.get()).isTrue();
    }
  }

  @Test
  @DisplayName("注册回调与调度器同属常驻条件_chat等交互命令仍零注册")
  void schedulerRegistrarSharesResidentCondition() throws Exception {
    Method factory =
        CoreEngineConfiguration.class.getDeclaredMethod("schedulerRegistrar", AgentScheduler.class);

    ConditionalOnProperty condition = factory.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("oryxos.scheduler");
    assertThat(condition.havingValue()).isEqualTo("true");
  }

  private static Profile profileWithSchedule() {
    return new Profile(
        "p",
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        List.of(new ScheduleConfig("morning-report", "0 0 9 * * *", "Asia/Shanghai", "日报")),
        List.of(),
        new Profile.Settings(10, 20),
        null,
        null);
  }

  private static Profile profile() {
    return new Profile(
        "p",
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        List.of(),
        new Profile.Settings(10, 20),
        null,
        null);
  }
}
