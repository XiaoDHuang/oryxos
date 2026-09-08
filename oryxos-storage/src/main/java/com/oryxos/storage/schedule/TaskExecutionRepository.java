package com.oryxos.storage.schedule;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 对应 task_executions 表的 Spring Data 仓储;终态写入用条件更新承载 watchdog 与 worker 的竞争.
 *
 * @author OryxOS Contributors
 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

  /**
   * 按任务分页取历史,排序由方法名固定(startedAt DESC, executionId DESC).
   *
   * @param taskId 任务标识
   * @param pageable 分页参数
   * @return 一页执行记录
   */
  List<TaskExecution> findByTaskIdOrderByStartedAtDescExecutionIdDesc(
      String taskId, Pageable pageable);

  /**
   * 统计某任务的历史总数.
   *
   * @param taskId 任务标识
   * @return 历史总条数
   */
  long countByTaskId(String taskId);

  /**
   * 启动恢复用:全部仍进行中的遗留记录.
   *
   * @return success 为 NULL 的执行记录
   */
  List<TaskExecution> findBySuccessIsNull();

  /**
   * 只对仍进行中的行写终态;先冲刷待写变更再执行,清空一级缓存防陈旧读.
   *
   * @param executionId 执行标识
   * @param success 终态成败
   * @param errorMessage 固定错误分类,成功为 null
   * @param durationMs 终结耗时
   * @return 更新行数,0 表示已被竞争者终结,调用方回读既有终态
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE TaskExecution e SET e.success = :success, e.errorMessage = :errorMessage,"
          + " e.durationMs = :durationMs WHERE e.executionId = :executionId AND e.success IS NULL")
  int applyTerminalIfRunning(
      @Param("executionId") Long executionId,
      @Param("success") Boolean success,
      @Param("errorMessage") String errorMessage,
      @Param("durationMs") Long durationMs);
}
