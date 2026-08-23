package com.oryxos.storage.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code llm_calls} 审计表的 Spring Data 访问. 核心阶段只写;查询/报表 API 属于扩展 阶段范围。
 *
 * @author OryxOS Contributors
 */
public interface LlmCallRepository extends JpaRepository<LlmCall, String> {}
