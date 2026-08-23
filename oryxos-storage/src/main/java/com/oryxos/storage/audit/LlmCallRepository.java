package com.oryxos.storage.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code llm_calls} audit table. Core stage writes only; query/reporting
 * APIs are extension-stage scope.
 *
 * @author OryxOS Contributors
 */
public interface LlmCallRepository extends JpaRepository<LlmCall, String> {}
