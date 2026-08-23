package com.oryxos.storage.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code tool_invocations} 审计表的 Spring Data 访问. 核心阶段只写。
 *
 * @author OryxOS Contributors
 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, String> {}
