package com.oryxos.core.schedule;

import com.oryxos.core.profile.ScheduleConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务状态与执行历史的唯一持久化端口. core 不感知存储实现;实现(storage)必须用短事务, 事务不得跨 LLM/Tool/网络调用。进行中先落库(begin),进程中断由下次启动的
 * {@link #recoverInterrupted()} 诚实标记 unknown,不自动重放。
 *
 * @author OryxOS Contributors
 */
public interface ScheduledTaskStore {

  /** 固定错误分类:模型调用失败(不存 provider 原始异常). */
  String ERROR_LLM = "模型调用失败";

  /** 固定错误分类:工具执行失败(不存工具参数). */
  String ERROR_TOOL = "工具执行失败";

  /** 固定错误分类:轮数耗尽. */
  String ERROR_ITERATIONS = "轮数耗尽";

  /** 固定错误分类:执行超时(60 秒公共截止). */
  String ERROR_TIMEOUT = "执行超时";

  /** 固定错误分类:进程中断结果未知(仅 recoverInterrupted 写,duration 保持 NULL). */
  String ERROR_UNKNOWN = "进程中断,执行结果未知";

  /**
   * 登记当前合法规则:新任务初始 enabled=true;同 id 同 Profile 只更新定义快照与 next_run, 旧 enabled/计数/历史不变;同 id 换 Profile
   * 拒绝.
   *
   * @param profileName 所属 Profile 名
   * @param definition 规则快照(id/cron/zone/message)
   * @param nextRunAt 调用方建议的下一次候选;实际保留的 enabled 为 false 时必须落 NULL
   * @return 登记后的任务视图
   */
  ScheduledTaskView register(String profileName, ScheduleConfig definition, Instant nextRunAt);

  /**
   * 按 taskId 升序列出全部任务(含已失效规则的行,可用性由调用方派生).
   *
   * @return 全量任务视图
   */
  List<ScheduledTaskView> listTasks();

  /**
   * 按 id 回读任务.
   *
   * @param taskId 任务标识
   * @return 存在则返回,否则空
   */
  Optional<ScheduledTaskView> findTask(String taskId);

  /**
   * 分页查询某任务的执行历史,按 startedAt DESC、executionId DESC 稳定排序.
   *
   * @param taskId 任务标识,不存在抛 NoSuchElementException
   * @param page 页码,从 0 开始,负数抛 IllegalArgumentException
   * @param size 每页条数,1–100,越界抛 IllegalArgumentException
   * @return 一页执行历史
   */
  List<TaskExecutionView> listExecutions(String taskId, int page, int size);

  /**
   * 返回某任务的执行历史总数;与分页读并发时允许快照差异,不得丢排序或重复终态.
   *
   * @param taskId 任务标识,不存在抛 NoSuchElementException
   * @return 历史总条数
   */
  long countExecutions(String taskId);

  /**
   * 持久化启停开关;只改 enabled/next_run,不动定义与历史,不影响已开始的执行.
   *
   * @param taskId 任务标识,不存在抛 NoSuchElementException
   * @param enabled 目标开关值
   * @param nextRunAt 启用时的下一次候选;停用时必须落 NULL
   * @return 更新后的任务视图
   */
  ScheduledTaskView setEnabled(String taskId, boolean enabled, Instant nextRunAt);

  /**
   * 更新下一次候选时间;规则失效(定义消失)时调用方传 NULL,行与历史保留.
   *
   * @param taskId 任务标识,不存在抛 NoSuchElementException
   * @param nextRunAt 新候选,可为 null
   */
  void updateNextRun(String taskId, Instant nextRunAt);

  /**
   * 在短事务内登记一次执行开始:插入 running 历史并更新任务 last_run_at/last_status/running/run_count+1.
   * 开始记账失败则不得进行任何引擎调用。
   *
   * @param taskId 任务标识,不存在抛 NoSuchElementException
   * @param sessionId SessionManager 返回的会话 id,本端口不生成
   * @param startedAt 实际准入时刻
   * @return 带持久化 executionId 的进行中视图
   */
  TaskExecutionView begin(String taskId, String sessionId, Instant startedAt);

  /**
   * 以 executionId 且 success IS NULL 为条件原子写终态并同步任务 last_status,不二次加次数. 重复 finish 返回已有终态,不覆盖
   * success/duration/error;终态写失败必须上抛, 不能伪报成功。
   *
   * @param executionId 执行标识
   * @param success 终态成败;进程中断的 unknown 由 recoverInterrupted 写,不经此方法
   * @param errorMessage 固定可公开的中文错误分类,成功时传 null
   * @param durationMs 终结耗时;未知耗时不允许用零占位
   * @return 终态视图
   */
  TaskExecutionView finish(long executionId, boolean success, String errorMessage, Long durationMs);

  /**
   * 仅本次进程启动、cron 安装前调用一次:把 success IS NULL 的遗留历史标记为 false/固定 unknown 文本/duration=NULL, 并将对应任务
   * last_status 置 unknown,计数不变;禁止恢复当前仍活着的另一实例.
   */
  void recoverInterrupted();
}
