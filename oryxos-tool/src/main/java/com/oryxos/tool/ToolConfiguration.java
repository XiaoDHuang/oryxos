package com.oryxos.tool;

import com.oryxos.core.tool.OryxTool;
import com.oryxos.memory.MemoryTools;
import com.oryxos.tool.builtin.FileTools;
import com.oryxos.tool.builtin.HttpTools;
import com.oryxos.tool.builtin.NotifyTools;
import com.oryxos.tool.builtin.ShellTools;
import com.oryxos.tool.mcp.McpClientService;
import com.oryxos.tool.notify.WebhookNotifyAdapter;
import com.oryxos.tool.sandbox.FileSandboxProperties;
import com.oryxos.tool.sandbox.HttpSandboxProperties;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.ShellSandboxProperties;
import com.oryxos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.client.RestClient;

/**
 * 注册完成后才发布不可变工具表，防止引擎捕获提前快照.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration
@EnableConfigurationProperties({
  FileSandboxProperties.class,
  ShellSandboxProperties.class,
  HttpSandboxProperties.class
})
class ToolConfiguration {

  @Value("${oryxos.root:.oryxos}")
  private String workspaceRoot = ".oryxos";

  @Bean
  ToolArgumentValidator toolArgumentValidator() {
    return new ToolArgumentValidator();
  }

  @Bean
  ToolRegistry toolRegistry(ToolArgumentValidator validator) {
    return new ToolRegistry(validator);
  }

  /** 返回类型收窄到具体类:除 Sandbox 端口注入外,管理端点需要按具体类取用运行时增删方法. */
  @Bean
  @ConditionalOnMissingBean(Sandbox.class)
  WhitelistSandbox sandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    return new WhitelistSandbox(fileProps, shellProps, httpProps);
  }

  @Bean
  FileTools fileTools(Sandbox sandbox) {
    return new FileTools(sandbox);
  }

  @Bean
  ShellTools shellTools(Sandbox sandbox) {
    return new ShellTools(sandbox);
  }

  @Bean
  HttpTools httpTools(Sandbox sandbox, RestClient.Builder builder) {
    return new HttpTools(sandbox, builder);
  }

  @Bean
  NotifyTools notifyTools(Sandbox sandbox, WebhookNotifyAdapter adapter) {
    return new NotifyTools(sandbox, adapter);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(McpClientService.class)
  McpClientService mcpClientService(
      ToolRegistry registry, Sandbox sandbox, ToolArgumentValidator validator) {
    return new McpClientService(
        Path.of(workspaceRoot).resolve("mcp_servers.yaml"),
        registry,
        sandbox,
        validator::validateSchema,
        validator::validateArguments);
  }

  @Bean("toolTable")
  Map<String, OryxTool> toolTable(
      ToolRegistry registry,
      FileTools files,
      ShellTools shell,
      HttpTools http,
      NotifyTools notify,
      ObjectProvider<MemoryTools> memoryTools,
      McpClientService mcp,
      ConfigurableListableBeanFactory factory) {
    List<Object> builtins = new ArrayList<>(List.of(files, shell, http, notify));
    memoryTools.ifAvailable(builtins::add);
    for (Object builtin : builtins) {
      // 内置方法自己在IO前enforce，普通Java插件不得获得这个包内接线许可。
      registry.registerAnnotated(builtin, () -> {});
    }
    for (String name : factory.getBeanDefinitionNames()) {
      Class<?> type = factory.getType(name, false);
      Object existing = factory.getSingleton(name);
      if (existing != null) {
        type = AopUtils.getTargetClass(existing);
      }
      if (type == null || !hasToolMethods(type)) {
        continue;
      }
      // 先查类型元数据再创建候选Bean，不能触发依赖toolTable的引擎消费者提前初始化。
      Object bean = factory.getBean(name);
      if (!builtins.contains(bean)) {
        registry.registerAnnotated(bean);
      }
    }
    mcp.connectAll();
    registry.freeze();
    return registry.asMap();
  }

  private static boolean hasToolMethods(Class<?> type) {
    return !MethodIntrospector.selectMethods(
            type,
            (MethodIntrospector.MetadataLookup<Tool>)
                method -> AnnotatedElementUtils.findMergedAnnotation(method, Tool.class))
        .isEmpty();
  }
}
