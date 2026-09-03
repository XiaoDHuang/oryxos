package com.oryxos.boot;

import com.oryxos.core.memory.MemoryService;
import com.oryxos.memory.LongTermMemoryStore;
import com.oryxos.memory.MemoryOutboundGuard;
import com.oryxos.tool.sandbox.ActionType;
import com.oryxos.tool.sandbox.Sandbox;
import com.oryxos.tool.sandbox.SandboxAction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 在组合层衔接记忆与工具安全端口，避免memory反向依赖tool.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration(
    beforeName = "com.oryxos.memory.Mem0PropertiesConfiguration",
    afterName = "com.oryxos.tool.ToolConfiguration")
@ConditionalOnProperty(name = "memory.backend", havingValue = "mem0")
@ConditionalOnMissingBean({MemoryService.class, LongTermMemoryStore.class})
class MemoryOutboundConfiguration {

  @Bean
  @ConditionalOnMissingBean(MemoryOutboundGuard.class)
  MemoryOutboundGuard memoryOutboundGuard(Sandbox sandbox) {
    return target -> {
      try {
        if (target == null) {
          throw new IllegalArgumentException();
        }
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.toString()));
      } catch (RuntimeException exception) {
        throw new SecurityException("记忆目标未获安全检查许可");
      }
    };
  }
}
