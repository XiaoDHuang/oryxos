package com.oryxos.core.agent;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.schedule.AgentScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动(及 30 节 API 新增)时扫描 {@code .oryxos/agents/} 的装配器(29 节):对每个 Agent 目录做"解析 → 派生 → 同一套校验 → 注册"三步,有
 * schedules 的再交给 {@link AgentScheduler}(课案插件化机制本体). 单目录失败记错误日志跳过,不阻断其余;
 * 与既有注册项同名跳过不覆盖(同名策略属扩展阶段);引用未注册能力告警不阻断。
 *
 * @author OryxOS Contributors
 */
public class AgentDirectoryScanner {

  private static final Logger LOGGER = LoggerFactory.getLogger(AgentDirectoryScanner.class);

  private final ProfileRegistry registry;

  private final AgentLoader loader = new AgentLoader();

  /** 已注册工具名集合;null 表示工具表不可判定(无 tool 模块),跳过告警而不是全量误报. */
  private final Set<String> registeredToolNames;

  /** 调度器;null 表示无调度器模式(chat 等交互命令),Agent 照注册、定时注册跳过. */
  private final AgentScheduler scheduler;

  /** 创建扫描器;工具名集合与调度器均可为 null(语义见字段注释). */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注册表与调度器是容器管理的单例协作者,扫描器的本职就是持有并驱动它们,防御性拷贝反而语义错误。")
  public AgentDirectoryScanner(
      ProfileRegistry registry, Set<String> registeredToolNames, AgentScheduler scheduler) {
    this.registry = registry;
    this.registeredToolNames = registeredToolNames == null ? null : Set.copyOf(registeredToolNames);
    this.scheduler = scheduler;
  }

  /** 扫描 agentsDir 全部子目录并注册,返回成功注册数;目录不存在/为空返回 0. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "所有外部来源的值在写入日志前都做了 CR/LF 清洗;被标记的参数是末尾的 Throwable,由 SLF4J 渲染为堆栈。")
  public int scan(Path agentsDir) {
    if (agentsDir == null || !Files.isDirectory(agentsDir)) {
      LOGGER.info("agents 目录 {} 不存在;未扫描任何 Agent", sanitize(agentsDir));
      return 0;
    }
    List<Path> dirs;
    try (Stream<Path> stream = Files.list(agentsDir)) {
      dirs = stream.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      LOGGER.error("列出 agents 目录 {} 失败: {}", sanitize(agentsDir), sanitize(e.getMessage()), e);
      return 0;
    }
    int registered = 0;
    for (Path dir : dirs) {
      if (registerOne(dir)) {
        registered++;
      }
    }
    LOGGER.info("agents 扫描完成: 新注册 {} 个 Agent(目录 {})", registered, sanitize(agentsDir));
    return registered;
  }

  /** 单目录三步:派生 → 同名检查 → 注册(校验在 register 内,与启动 YAML 同一异常同一消息) → 可选注册定时. */
  private boolean registerOne(Path dir) {
    Profile profile;
    try {
      profile = loader.deriveProfile(dir);
    } catch (IllegalArgumentException e) {
      LOGGER.error("跳过 Agent 目录 {}: {}", sanitize(dir), sanitize(e.getMessage()));
      return false;
    }
    if (registry.exists(profile.name())) {
      LOGGER.error("Agent 目录 {} 与既有注册项同名,跳过派生注册(不覆盖)", sanitize(dir));
      return false;
    }
    warnUnknownTools(profile, dir);
    try {
      registry.register(profile);
    } catch (IllegalArgumentException e) {
      LOGGER.error("跳过 Agent 目录 {}: {}", sanitize(dir), sanitize(e.getMessage()));
      return false;
    }
    if (scheduler != null && !profile.schedules().isEmpty()) {
      scheduler.registerProfile(profile);
    }
    LOGGER.info(
        "已注册 Agent 目录 {}(name={}, 定时 {} 条)",
        sanitize(dir),
        sanitize(profile.name()),
        profile.schedules().size());
    return true;
  }

  /** 引用底座未注册的能力:告警点名能力名与 Agent 名,不阻断;工具表不可判定时整体跳过告警. */
  private void warnUnknownTools(Profile profile, Path dir) {
    if (registeredToolNames == null) {
      return;
    }
    for (String tool : profile.tools()) {
      if (!registeredToolNames.contains(tool)) {
        LOGGER.warn(
            "Agent {} 引用了底座未注册的能力 {}(目录 {})",
            sanitize(profile.name()),
            sanitize(tool),
            sanitize(dir));
      }
    }
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
