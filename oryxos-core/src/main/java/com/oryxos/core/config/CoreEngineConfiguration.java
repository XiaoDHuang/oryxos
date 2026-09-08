package com.oryxos.core.config;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.memory.MemoryService;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileLoader;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.react.ReActLoop;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.tool.OryxTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 引擎装配点. 17 节的引擎类刻意保持纯 POJO(不带 Spring 注解,单测容易),需要引擎的命令 (chat/serve/gateway)经这里把它们装成
 * Bean.工具模块完成注册后发布toolTable；没有该模块时保持空表，不依赖下游注册表类型.
 *
 * @author OryxOS Contributors
 */
@Configuration
public class CoreEngineConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(CoreEngineConfiguration.class);

  @Value("${oryxos.root:.oryxos}")
  private String workspaceRoot = ".oryxos";

  /** 工作区未初始化时给出可操作的错误,而不是一堆 Bean 创建失败. */
  private Path requireWorkspace() {
    Path workspace = Path.of(workspaceRoot);
    if (!Files.isDirectory(workspace)) {
      throw new IllegalStateException("未找到配置的工作区 —— 请先运行 oryxos init");
    }
    return workspace;
  }

  /** 加载全部 Profile 并建内存索引;全局 provider 名集合由 provider 模块按类型供入. */
  @Bean
  public ProfileRegistry profileRegistry(ObjectProvider<Set<String>> globalProviderNames) {
    Path workspace = requireWorkspace();
    Set<String> names = globalProviderNames.getIfAvailable(Set::of);
    List<Profile> profiles = new ProfileLoader(names).loadAll(workspace.resolve("profiles"));
    LOGGER.info("已加载 {} 个 Profile", profiles.size());
    return new ProfileRegistry(profiles);
  }

  /** 上下文供给器:Bootstrap/Skill 文件每次现读,无缓存. */
  @Bean
  public ContextLoader contextLoader() {
    return new ContextLoader(requireWorkspace());
  }

  /** 提示词装配器;工具表来自 toolTable Bean(缺省空表). */
  @Bean
  public PromptBuilder promptBuilder(
      ContextLoader contextLoader,
      ObjectProvider<MemoryService> memoryService,
      @Qualifier("toolTable") ObjectProvider<Map<String, OryxTool>> toolTable) {
    Map<String, OryxTool> tools = toolTable.getIfAvailable(Map::of);
    MemoryService memory = memoryService.getIfAvailable();
    return memory == null
        ? new PromptBuilder(contextLoader, tools)
        : new PromptBuilder(contextLoader, tools, memory);
  }

  /** 工具执行器:与 PromptBuilder 共享同一张工具表. */
  @Bean
  public ToolExecutor toolExecutor(
      @Qualifier("toolTable") ObjectProvider<Map<String, OryxTool>> toolTable,
      ToolInvocationAudit toolInvocationAudit) {
    return new ToolExecutor(toolTable.getIfAvailable(Map::of), toolInvocationAudit);
  }

  /** ReAct 循环本体. */
  @Bean
  public ReActLoop reActLoop(
      LlmGateway llmGateway, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
    return new ReActLoop(llmGateway, promptBuilder, toolExecutor);
  }

  /** 三种触发源共用的统一入口. */
  @Bean
  public AgentService agentService(
      ReActLoop reActLoop, ProfileRegistry profileRegistry, SessionManager sessionManager) {
    return new AgentService(reActLoop, profileRegistry, sessionManager);
  }

  /** Boot 的虚拟线程自动配置不会保证提供这个具体类型,常驻模式须显式交给容器托管. */
  @Bean
  @ConditionalOnProperty(prefix = "oryxos.scheduler", name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean(ThreadPoolTaskScheduler.class)
  ThreadPoolTaskScheduler residentTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setThreadNamePrefix("oryxos-scheduler-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  /**
   * 定时调度器(第三种触发源). 仅常驻模式存在该 Bean:serve/gateway 启动时传入 {@code
   * oryxos.scheduler.enabled=true}(§8.6,用户决议);chat 等交互命令缺省该属性,条件不满足, 容器中连 Bean 都没有,定时触发注册数恒为零。
   */
  @Bean
  @ConditionalOnProperty(prefix = "oryxos.scheduler", name = "enabled", havingValue = "true")
  public AgentScheduler agentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      SimpleAsyncTaskExecutor schedulerWorkerExecutor,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore scheduledTaskStore) {
    return new AgentScheduler(
        taskScheduler,
        schedulerWorkerExecutor,
        profileRegistry,
        agentService,
        sessionManager,
        scheduledTaskStore);
  }

  /** 定时执行用的虚拟 worker 执行器:cron 线程只派发,引擎跑在 Spring 管理的虚拟线程上(宪法 VII). 关闭时取消残余线程并有界等待,不建固定池。 */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "oryxos.scheduler", name = "enabled", havingValue = "true")
  SimpleAsyncTaskExecutor schedulerWorkerExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("scheduler-worker-");
    executor.setVirtualThreads(true);
    executor.setTaskTerminationTimeout(5000L);
    executor.setCancelRemainingTasksOnClose(true);
    return executor;
  }

  /**
   * 注册时机挪到上下文初始化完成之后:schema/Store 先就位,再恢复遗留 running 并安装 cron, 避免 JPA 写入早于数据库初始化(恢复只此一次,不在每次
   * registerAll 误标在途任务).
   */
  @Bean
  @ConditionalOnProperty(prefix = "oryxos.scheduler", name = "enabled", havingValue = "true")
  SmartInitializingSingleton schedulerRegistrar(AgentScheduler agentScheduler) {
    return () -> {
      agentScheduler.recoverInterrupted();
      agentScheduler.registerAll();
    };
  }
}
