package com.oryxos.memory;

import com.oryxos.core.memory.MemoryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 内建Mem0被选中且没有用户门面时才解析其Secret与远端参数.
 *
 * @author OryxOS Contributors
 */
@AutoConfiguration(before = MemoryConfiguration.class)
@ConditionalOnProperty(name = "memory.backend", havingValue = "mem0")
@ConditionalOnMissingBean({MemoryService.class, LongTermMemoryStore.class})
@EnableConfigurationProperties(Mem0Properties.class)
class Mem0PropertiesConfiguration {}
