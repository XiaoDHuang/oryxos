package com.oryxos.core.config;

import com.oryxos.core.context.ContextLoader;
import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileLoader;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.react.LlmGateway;
import com.oryxos.core.react.PromptBuilder;
import com.oryxos.core.react.ReActLoop;
import com.oryxos.core.react.ToolExecutor;
import com.oryxos.core.react.ToolInvocationAudit;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎装配点. 17 节的引擎类刻意保持纯 POJO(不带 Spring 注解,单测容易),需要引擎的命令 (chat/serve/gateway)经这里把它们装成
 * Bean。核心阶段工具表为空——注册表与内置工具由 Tool 课(20 节)填装,这里只留 Map 注入位。
 *
 * @author OryxOS Contributors
 */
@Configuration
public class CoreEngineConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(CoreEngineConfiguration.class);

  private static final Path WORKSPACE_DIR = Path.of(".oryxos");

  /** 工作区未初始化时给出可操作的错误,而不是一堆 Bean 创建失败. */
  private static Path requireWorkspace() {
    if (!Files.isDirectory(WORKSPACE_DIR)) {
      throw new IllegalStateException("未找到 .oryxos 工作区 —— 请先运行 oryxos init");
    }
    return WORKSPACE_DIR;
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
      ContextLoader contextLoader, ObjectProvider<Map<String, OryxTool>> toolTable) {
    return new PromptBuilder(contextLoader, toolTable.getIfAvailable(Map::of));
  }

  /** 工具执行器:与 PromptBuilder 共享同一张工具表. */
  @Bean
  public ToolExecutor toolExecutor(
      ObjectProvider<Map<String, OryxTool>> toolTable, ToolInvocationAudit toolInvocationAudit) {
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
}
