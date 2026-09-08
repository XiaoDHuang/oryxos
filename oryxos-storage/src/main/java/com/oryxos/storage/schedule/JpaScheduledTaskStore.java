package com.oryxos.storage.schedule;

import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.schedule.ScheduledTaskView;
import com.oryxos.core.schedule.TaskExecutionView;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定时任务 Store 的 SQLite/JPA 实现. begin/finish/recover 用短事务,事务不跨任何模型/工具/网络调用;
 * 时间与成败分类在实体(Long/Boolean)与视图(Instant/Boolean)间按契转换,未知耗时不许用零占位。
 *
 * @author OryxOS Contributors
 */
@Component
public final class JpaScheduledTaskStore implements ScheduledTaskStore {

  private static final String STATUS_RUNNING = "running";

  private static final String STATUS_SUCCESS = "success";

  private static final String STATUS_FAILED = "failed";

  private static final String STATUS_TIMEOUT = "timeout";

  private static final String STATUS_UNKNOWN = "unknown";

  private static final int MAX_PAGE_SIZE = 100;

  private final ScheduledTaskRepository taskRepository;

  private final TaskExecutionRepository executionRepository;

  private final TransactionTemplate transactions;

  /** 以两个仓储与事务管理器创建 Store. */
  public JpaScheduledTaskStore(
      ScheduledTaskRepository taskRepository,
      TaskExecutionRepository executionRepository,
      PlatformTransactionManager transactionManager) {
    this.taskRepository = taskRepository;
    this.executionRepository = executionRepository;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** 登记:新任务初始 enabled=true;同 id 同 Profile 更新定义但保留开关/计数/历史;同 id 换 Profile 拒绝. */
  @Override
  public ScheduledTaskView register(
      String profileName, ScheduleConfig definition, Instant nextRunAt) {
    validateDefinition(definition);
    return taskRepository
        .findById(definition.id())
        .map(existing -> registerExisting(existing, profileName, definition, nextRunAt))
        .orElseGet(() -> registerNew(profileName, definition, nextRunAt));
  }

  private ScheduledTaskView registerExisting(
      ScheduledTask existing, String profileName, ScheduleConfig definition, Instant nextRunAt) {
    if (!existing.getProfileName().equals(profileName)) {
      throw new IllegalArgumentException(
          "任务 id 已属于其他 Profile: " + definition.id() + " (现属 " + existing.getProfileName() + ")");
    }
    // 保留 enabled 并以保留值决定 next_run:停用的任务不能因为调用方按启用算就复活候选
    Long next = Boolean.TRUE.equals(existing.getEnabled()) ? millis(nextRunAt) : null;
    existing.updateDefinition(definition.cron(), definition.zone(), definition.message(), next);
    return toView(taskRepository.save(existing));
  }

  private ScheduledTaskView registerNew(
      String profileName, ScheduleConfig definition, Instant nextRunAt) {
    ScheduledTask created =
        new ScheduledTask(
            definition.id(),
            profileName,
            definition.cron(),
            definition.zone(),
            definition.message(),
            true,
            millis(nextRunAt),
            null,
            null,
            0L);
    return toView(taskRepository.save(created));
  }

  /** 全部任务按 taskId 升序(含失效规则的行). */
  @Override
  public List<ScheduledTaskView> listTasks() {
    return taskRepository.findAll(Sort.by(Sort.Direction.ASC, "taskId")).stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  /** 按 id 回读. */
  @Override
  public java.util.Optional<ScheduledTaskView> findTask(String taskId) {
    return taskRepository.findById(taskId).map(JpaScheduledTaskStore::toView);
  }

  /** 分页历史:先核任务存在,再校验页参,排序由仓储方法名固定. */
  @Override
  public List<TaskExecutionView> listExecutions(String taskId, int page, int size) {
    requireTask(taskId);
    if (page < 0) {
      throw new IllegalArgumentException("页码不能为负数: " + page);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("每页条数须为 1-100: " + size);
    }
    return executionRepository
        .findByTaskIdOrderByStartedAtDescExecutionIdDesc(taskId, PageRequest.of(page, size))
        .stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  /** 历史总数:未知任务同样按 NoSuchElementException. */
  @Override
  public long countExecutions(String taskId) {
    requireTask(taskId);
    return executionRepository.countByTaskId(taskId);
  }

  /** 启停:只改开关与候选,已开始的执行不受影响. */
  @Override
  public ScheduledTaskView setEnabled(String taskId, boolean enabled, Instant nextRunAt) {
    ScheduledTask task = requireTask(taskId);
    task.applyEnabled(enabled, enabled ? millis(nextRunAt) : null);
    return toView(taskRepository.save(task));
  }

  /** 候选时间更新(失效规则传 null). */
  @Override
  public void updateNextRun(String taskId, Instant nextRunAt) {
    ScheduledTask task = requireTask(taskId);
    task.applyNextRun(millis(nextRunAt));
    taskRepository.save(task);
  }

  /** Begin:单事务内插 running 历史并同步任务计数/状态;任何一步失败都不留半笔. */
  @Override
  public TaskExecutionView begin(String taskId, String sessionId, Instant startedAt) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId 不能为空");
    }
    return transactions.execute(
        status -> {
          ScheduledTask task = requireTask(taskId);
          TaskExecution execution =
              executionRepository.save(new TaskExecution(taskId, sessionId, millis(startedAt)));
          task.markRunStarted(millis(startedAt));
          taskRepository.save(task);
          return toView(execution);
        });
  }

  /** Finish:条件终态与任务状态同事务;重复 finish 回读既有终态,不覆盖. */
  @Override
  public TaskExecutionView finish(
      long executionId, boolean success, String errorMessage, Long durationMs) {
    return transactions.execute(
        status -> {
          int updated =
              executionRepository.applyTerminalIfRunning(
                  executionId, success, errorMessage, durationMs);
          TaskExecution execution =
              executionRepository
                  .findById(executionId)
                  .orElseThrow(() -> new NoSuchElementException("执行记录不存在: " + executionId));
          if (updated == 0) {
            // 已被竞争者终结:只回读,不二次更新任务状态
            return toView(execution);
          }
          ScheduledTask task = requireTask(execution.getTaskId());
          task.applyTerminalStatus(terminalOf(success, errorMessage));
          taskRepository.save(task);
          return toView(execution);
        });
  }

  /** 启动恢复:遗留 running 全部标记固定 unknown 文本,任务最近状态置 unknown,计数不动. */
  @Override
  public void recoverInterrupted() {
    transactions.executeWithoutResult(
        status -> {
          List<TaskExecution> interrupted = executionRepository.findBySuccessIsNull();
          Set<String> affected = new HashSet<>();
          for (TaskExecution execution : interrupted) {
            execution.applyUnknown(ERROR_UNKNOWN);
            affected.add(execution.getTaskId());
          }
          executionRepository.saveAll(interrupted);
          for (String taskId : affected) {
            taskRepository
                .findById(taskId)
                .ifPresent(
                    task -> {
                      task.applyUnknownStatus();
                      taskRepository.save(task);
                    });
          }
        });
  }

  private static String terminalOf(boolean success, String errorMessage) {
    if (success) {
      return STATUS_SUCCESS;
    }
    return ERROR_TIMEOUT.equals(errorMessage) ? STATUS_TIMEOUT : STATUS_FAILED;
  }

  private ScheduledTask requireTask(String taskId) {
    return taskRepository
        .findById(taskId)
        .orElseThrow(() -> new NoSuchElementException("任务不存在: " + taskId));
  }

  private static void validateDefinition(ScheduleConfig definition) {
    if (definition == null || definition.id() == null || definition.id().isBlank()) {
      throw new IllegalArgumentException("任务 id 不能为空");
    }
    if (definition.cron() == null
        || definition.cron().isBlank()
        || definition.zone() == null
        || definition.zone().isBlank()
        || definition.message() == null
        || definition.message().isBlank()) {
      throw new IllegalArgumentException("任务定义要素(cron/zone/message)不能为空: " + definition.id());
    }
  }

  private static ScheduledTaskView toView(ScheduledTask entity) {
    return new ScheduledTaskView(
        entity.getTaskId(),
        entity.getProfileName(),
        entity.getCron(),
        entity.getZone(),
        entity.getMessage(),
        Boolean.TRUE.equals(entity.getEnabled()),
        instant(entity.getNextRunAt()),
        instant(entity.getLastRunAt()),
        entity.getLastStatus(),
        entity.getRunCount() == null ? 0L : entity.getRunCount());
  }

  private static TaskExecutionView toView(TaskExecution entity) {
    return new TaskExecutionView(
        entity.getExecutionId(),
        entity.getTaskId(),
        entity.getSessionId(),
        instant(entity.getStartedAt()),
        entity.getSuccess(),
        entity.getErrorMessage(),
        entity.getDurationMs());
  }

  private static Long millis(Instant value) {
    return value == null ? null : value.toEpochMilli();
  }

  private static Instant instant(Long value) {
    return value == null ? null : Instant.ofEpochMilli(value);
  }
}
