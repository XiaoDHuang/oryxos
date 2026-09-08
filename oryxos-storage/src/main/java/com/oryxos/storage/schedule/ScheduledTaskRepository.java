package com.oryxos.storage.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 对应 scheduled_tasks 表的 Spring Data 仓储.
 *
 * @author OryxOS Contributors
 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {}
