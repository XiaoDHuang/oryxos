package com.oryxos.memory;

import com.oryxos.core.memory.MemoryService;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Memory实现保持在能力模块,core只看到端口.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
class MemoryConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "memory.backend", havingValue = "markdown", matchIfMissing = true)
  static class MarkdownConfiguration {

    @Bean
    @ConditionalOnMissingBean({
      LongTermMemory.class,
      LongTermMemoryStore.class,
      MemoryService.class
    })
    LongTermMemory longTermMemory() {
      return new LongTermMemory(Path.of(".oryxos"));
    }

    @Bean
    @ConditionalOnMissingBean({LongTermMemoryStore.class, MemoryService.class})
    LongTermMemoryStore markdownMemoryStore(LongTermMemory memory) {
      return new MarkdownMemoryStore(memory);
    }
  }

  @Bean
  @ConditionalOnMissingBean(MemoryService.class)
  MemoryService memoryService(LongTermMemoryStore store) {
    return new MemoryServiceImpl(store);
  }

  @Bean
  @ConditionalOnMissingBean(MemoryTools.class)
  MemoryTools memoryTools(MemoryService memoryService) {
    return new MemoryTools(memoryService);
  }
}
