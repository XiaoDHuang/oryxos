package com.oryxos.core.schedule;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.profile.ScheduleConfig;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 第三种触发源(钟推):启动时把各 Profile 声明的定时规则动态注册进 Spring 调度器,到点拼消息交给与人推完全相同的 {@link AgentService#process} 入口.
 * 模块只管"到点喊一声":消息说什么归 Profile/SKILL.md,交上去怎么处理归 ReActLoop. 保持纯 POJO,由 CoreEngineConfiguration
 * 装配(仅常驻模式调用 registerAll,见 §8.6)。
 *
 * @author OryxOS Contributors
 */
public class AgentScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentScheduler.class);

  /** 钟推的固定 channel:与 user 一起构成固定三元组,同 Profile 历次定时触发复用同一 Session(§8.5). */
  private static final String SCHEDULER_CHANNEL = "scheduler";

  /** 钟推的固定 user(语义见 channel 常量). */
  private static final String SCHEDULER_USER = "scheduler";

  private final ThreadPoolTaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;

  /** 按规则 id 的进程内锁表:防同一任务重叠执行. 核心阶段单实例,本地锁足够,分布式协调属扩展阶段. */
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  /** 创建调度器. 构造无副作用,注册行为全部在 {@link #registerAll()}. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者是 Spring 容器管理的单例 Bean,调度器的本职就是持有并驱动它们,防御性拷贝反而语义错误。")
  public AgentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /**
   * 启动时扫描全部 Profile 的 schedules 并逐条注册. 非法规则(要素缺失/cron 或时区不可解析/id 重复)记错误日志后跳过, 不阻断启动、不影响其他规则——对齐
   * ProfileLoader 校验失败的处置哲学. 注册来自配置而非编译期 @Scheduled,改规则不用改代码。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "规则四要素与 profile 名来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入),日志仅落本地运维通道。")
  public void registerAll() {
    Set<String> registeredIds = new HashSet<>();
    for (Profile profile : profileRegistry.all()) {
      for (ScheduleConfig schedule : profile.schedules()) {
        CronTrigger trigger = buildTrigger(profile, schedule, registeredIds);
        if (trigger == null) {
          continue;
        }
        taskScheduler.schedule(() -> runOnce(profile, schedule), trigger);
        registeredIds.add(schedule.id());
        LOGGER.info(
            "已注册定时任务 {}(profile={}, cron={}, zone={})",
            schedule.id(),
            profile.name(),
            schedule.cron(),
            schedule.zone());
      }
    }
  }

  /** 构造触发器;任何一步非法都返回 null 并由本方法记日志,调用方据此跳过. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "同上:被记录的非法值全部来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入)。")
  private CronTrigger buildTrigger(
      Profile profile, ScheduleConfig schedule, Set<String> registeredIds) {
    if (isBlank(schedule.id())
        || isBlank(schedule.cron())
        || isBlank(schedule.zone())
        || isBlank(schedule.message())) {
      LOGGER.error("定时规则四要素(id/cron/zone/message)均不得为空,跳过注册: profile={}", profile.name());
      return null;
    }
    if (registeredIds.contains(schedule.id())) {
      // 锁按裸 id 持有,id 撞车会让不同任务互相阻塞,必须以唯一性约束根除
      LOGGER.error("定时规则 id 重复: {}, 后者跳过注册(profile={})", schedule.id(), profile.name());
      return null;
    }
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(schedule.zone());
    } catch (DateTimeException e) {
      LOGGER.error("定时规则 {} 的时区不可识别: {}, 跳过注册", schedule.id(), schedule.zone());
      return null;
    }
    try {
      // cron 与时区一起传入,不让服务器系统时区替用户做主
      return new CronTrigger(schedule.cron(), zoneId);
    } catch (IllegalArgumentException e) {
      LOGGER.error("定时规则 {} 的 cron 表达式非法: {}, 跳过注册", schedule.id(), schedule.cron());
      return null;
    }
  }

  /**
   * 单次触发:拿锁(拿不到说明上次没跑完,本次直接跳过不排队)→ 固定三元组取会话 → 走统一处理入口. 失败只记日志, 调度器不崩;finally 必放锁,不会永久卡死. 包私有可见性供
   * harness 直调,不必真等 cron 到点。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "同上:任务 id 来自本机管理员编写的 Profile YAML(可信本地配置,非外部输入)。")
  void runOnce(Profile profile, ScheduleConfig schedule) {
    Lock lock = lockFor(schedule.id());
    if (!lock.tryLock()) {
      LOGGER.info("定时任务 {} 上一次仍未结束,本次触发跳过", schedule.id());
      return;
    }
    try {
      Session session =
          sessionManager.getOrCreate(SCHEDULER_CHANNEL, SCHEDULER_USER, profile.name());
      agentService.process(session, schedule.message());
    } catch (Exception e) {
      LOGGER.error("定时任务 {} 执行失败", schedule.id(), e);
    } finally {
      lock.unlock();
    }
  }

  /** 取任务 id 对应的锁(不存在则建). 包私有:harness 用它模拟"上一次还占着锁". */
  Lock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
