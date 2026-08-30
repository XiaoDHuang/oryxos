package com.oryxos.memory;

import com.oryxos.core.memory.MemoryService;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Memory实现保持在能力模块,core只看到端口.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration
class MemoryConfiguration {

  @Bean
  @ConditionalOnMissingBean(LongTermMemory.class)
  LongTermMemory longTermMemory() {
    return new LongTermMemory(Path.of(".oryxos"));
  }

  @Bean
  @ConditionalOnMissingBean(MemoryService.class)
  MemoryService memoryService(LongTermMemory longTermMemory) {
    return new MemoryServiceImpl(longTermMemory);
  }

  @Bean
  @ConditionalOnMissingBean(MemoryTools.class)
  MemoryTools memoryTools(MemoryService memoryService) {
    return new MemoryTools(memoryService);
  }
}
