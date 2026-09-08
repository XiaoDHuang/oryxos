package com.oryxos.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.schedule.ScheduledTaskView;
import com.oryxos.core.schedule.TaskExecutionView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理端点(列表/历史/立即执行/启停). 薄壳:校验与契约翻译在本地(三类 domain 异常显式转 既有 OryxException),业务状态全部来自
 * ScheduledTaskStore/AgentScheduler;非调度模式(chat)下 GET 只读可用(available=false),写操作 400。
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/schedules")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class ScheduleApiController {

  private final ScheduledTaskStore store;

  private final ObjectProvider<AgentScheduler> schedulerProvider;

  /** 以任务 Store 与可选调度器创建薄 Controller(非调度模式调度器 Bean 不存在). */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的是 Spring 容器单例 Bean,引用共享是依赖注入的本义。")
  public ScheduleApiController(
      ScheduledTaskStore store, ObjectProvider<AgentScheduler> schedulerProvider) {
    this.store = store;
    this.schedulerProvider = schedulerProvider;
  }

  /** 列出全部任务(核心十 Agent 规模,全量);available 由当前 catalog 派生,不落库. */
  @GetMapping
  public ApiResponse<List<TaskView>> list() {
    AgentScheduler scheduler = schedulerProvider.getIfAvailable();
    List<TaskView> tasks =
        store.listTasks().stream()
            .map(
                task ->
                    TaskView.of(task, scheduler != null && scheduler.isRegistered(task.taskId())))
            .toList();
    return ApiResponse.success(tasks);
  }

  /** 分页执行历史;未知任务 404,非法页参 400. */
  @GetMapping("/{id}/executions")
  public ApiResponse<ExecutionPageResponse> executions(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    try {
      long total = store.countExecutions(id);
      List<ExecutionView> content =
          store.listExecutions(id, page, size).stream().map(ExecutionView::of).toList();
      return ApiResponse.success(new ExecutionPageResponse(page, size, total, content));
    } catch (NoSuchElementException e) {
      throw new OryxException(ErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  /**
   * 立即执行一次并等待结果. Callable 复用 MVC 异步与 60 秒配置;调度器 watchdog 独立于请求取消, MVC 先 504
   * 也不会让执行线程无人看护——客户端可经执行历史复核真实终态。
   */
  @PostMapping("/{id}/run")
  public Callable<ApiResponse<ExecutionView>> run(@PathVariable String id) {
    AgentScheduler scheduler = requireScheduler();
    return () -> {
      TaskExecutionView view;
      try {
        view = scheduler.runNow(id);
      } catch (NoSuchElementException e) {
        throw new OryxException(ErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
      } catch (IllegalArgumentException | RejectedExecutionException e) {
        throw new OryxException(ErrorCode.INVALID_REQUEST, e.getMessage());
      }
      if (Boolean.FALSE.equals(view.success())
          && ScheduledTaskStore.ERROR_TIMEOUT.equals(view.errorMessage())) {
        throw new OryxException(ErrorCode.AGENT_TIMEOUT, "执行超时,终态已记账,可经执行历史复核");
      }
      return ApiResponse.success(ExecutionView.of(view));
    };
  }

  /** 启停开关;严格只接 {"enabled": boolean},任何其他字段/类型/缺字段一律 400,不发改任务定义. */
  @PutMapping("/{id}")
  public ApiResponse<TaskView> toggle(@PathVariable String id, @RequestBody JsonNode body) {
    AgentScheduler scheduler = requireScheduler();
    if (body == null
        || body.size() != 1
        || !body.has("enabled")
        || !body.get("enabled").isBoolean()) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, "请求体必须是且仅是 {\"enabled\": true|false}");
    }
    try {
      ScheduledTaskView updated = scheduler.setEnabled(id, body.get("enabled").asBoolean());
      return ApiResponse.success(TaskView.of(updated, scheduler.isRegistered(id)));
    } catch (NoSuchElementException e) {
      throw new OryxException(ErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException | RejectedExecutionException e) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  private AgentScheduler requireScheduler() {
    AgentScheduler scheduler = schedulerProvider.getIfAvailable();
    if (scheduler == null) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, "当前模式未启用调度(仅 serve/gateway)");
    }
    return scheduler;
  }

  /** 任务视图:Store 持久化字段 + 调度器派生的 available. */
  public record TaskView(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      boolean enabled,
      java.time.Instant nextRunAt,
      java.time.Instant lastRunAt,
      String lastStatus,
      long runCount,
      boolean available) {

    private static TaskView of(ScheduledTaskView view, boolean available) {
      return new TaskView(
          view.taskId(),
          view.profileName(),
          view.cron(),
          view.zone(),
          view.message(),
          view.enabled(),
          view.nextRunAt(),
          view.lastRunAt(),
          view.lastStatus(),
          view.runCount(),
          available);
    }
  }

  /** 执行历史视图;executionId 按十进制字符串输出,避免浏览器整数精度损失. */
  public record ExecutionView(
      String executionId,
      String taskId,
      String sessionId,
      java.time.Instant startedAt,
      Boolean success,
      String errorMessage,
      Long durationMs) {

    private static ExecutionView of(TaskExecutionView view) {
      return new ExecutionView(
          String.valueOf(view.executionId()),
          view.taskId(),
          view.sessionId(),
          view.startedAt(),
          view.success(),
          view.errorMessage(),
          view.durationMs());
    }
  }

  /** 执行历史分页信封. */
  public record ExecutionPageResponse(int page, int size, long total, List<ExecutionView> content) {

    /** 防御性复制,保持信封不可变. */
    public ExecutionPageResponse {
      content = content == null ? List.of() : List.copyOf(content);
    }
  }
}
