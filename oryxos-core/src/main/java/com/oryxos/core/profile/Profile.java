package com.oryxos.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 的完整配置,解析自 {@code .oryxos/profiles/*.yaml}. 所有字段都在本节创建; 后续各节消费各自负责的字段。YAML 键为
 * snake_case,由 {@link ProfileLoader} 在映射前 归一化为 camelCase。
 *
 * @author OryxOS Contributors
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Profile(
    String name,
    String description,
    Identity identity,
    Provider provider,
    List<String> tools,
    List<String> skills,
    List<String> mcpServers,
    List<String> channels,
    List<Map<String, Object>> notifyChannels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings,
    String createdAt,
    String updatedAt) {

  /** 规范构造器:null 集合归一为空集合,缺失的 settings 补上默认值. */
  public Profile {
    tools = tools == null ? List.of() : List.copyOf(tools);
    skills = skills == null ? List.of() : List.copyOf(skills);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of() : List.copyOf(channels);
    notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
    settings = settings == null ? new Settings(null, null) : settings;
  }

  /**
   * 身份块:agent 名,外加内联 prompt 或 prompt 文件(二选一,不可同时).
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Identity(String agentName, String prompt, String promptFile) {}

  /**
   * Provider 选择:调用哪个全局声明的 provider、用哪个模型与温度. {@code fallback} 为扩展阶段预留,尚未实现。
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provider(String name, String model, Double temperature, String fallback) {}

  /**
   * 运行时旋钮. 默认值来自需求文档:maxIterations=10,maxHistoryTurns=20。
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Settings(Integer maxIterations, Integer maxHistoryTurns) {

    /** Profile 未设置时的默认 ReAct 轮数上限. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 会话上下文中保留的最近对话轮数默认值. */
    public static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    /** 应用文档约定默认值的规范构造器. */
    public Settings {
      if (maxIterations == null) {
        maxIterations = DEFAULT_MAX_ITERATIONS;
      }
      if (maxHistoryTurns == null) {
        maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
      }
    }
  }
}
