package com.oryxos.core.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * An Agent's complete configuration, parsed from {@code .oryxos/profiles/*.yaml}. All fields are
 * created in this lesson; later lessons consume whichever field they own. YAML keys are snake_case
 * and normalized to camelCase by {@link ProfileLoader} before mapping.
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
    List<Map<String, Object>> schedules,
    List<String> bootstrap,
    Settings settings,
    String createdAt,
    String updatedAt) {

  /** Canonical constructor: null collections become empty, missing settings get defaults. */
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
   * Identity block: agent name plus an inline prompt or a prompt file (either, not both).
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Identity(String agentName, String prompt, String promptFile) {}

  /**
   * Provider selection: which globally-declared provider to call, with which model and temperature.
   * {@code fallback} is reserved for the extension stage and not implemented.
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provider(String name, String model, Double temperature, String fallback) {}

  /**
   * Runtime knobs. Defaults come from the demand document: maxIterations=10, maxHistoryTurns=20.
   *
   * @author OryxOS Contributors
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Settings(Integer maxIterations, Integer maxHistoryTurns) {

    /** Default ReAct iteration cap when the Profile leaves it unset. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** Default number of recent dialogue turns kept in the session context. */
    public static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    /** Canonical constructor applying documented defaults. */
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
