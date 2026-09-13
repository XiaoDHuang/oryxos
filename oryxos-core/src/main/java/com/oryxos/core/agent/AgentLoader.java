package com.oryxos.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * 解析 {@code .oryxos/agents/<name>/} 目录(29 节):读主文件 {@code AGENT.md},拆出 frontmatter(运行配置)与正文(任务指令),
 * 记录 {@code scripts/}、{@code skills/}、{@code REFERENCE.md} 等资源位置;{@link #deriveProfile} 把
 * frontmatter 派生成底座 认识的同一 {@link Profile}(课案:派生而非另起一套,零改动复用整台底座). 键归一化与 ${ENV} 解析规则与 ProfileLoader
 * 逐字对齐 (同一作者契约;若 ProfileLoader 的规则演进,此处必须同步)。
 *
 * @author OryxOS Contributors
 */
public class AgentLoader {

  private static final String AGENT_FILE = "AGENT.md";

  private static final String FENCE = "---";

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private static final String KEY_SEGMENT_SEPARATOR = "_";

  /** 身份块在 frontmatter 中的键. */
  private static final String IDENTITY_KEY = "identity";

  /** 注入到身份块的 prompt 文件键(与 Profile.Identity 的 record 分量同名). */
  private static final String PROMPT_FILE_KEY = "promptFile";

  private final ObjectMapper mapper = new ObjectMapper();

  /** 解析一个 Agent 目录;目录缺失/缺主文件/缺围栏/坏 YAML 抛 IllegalArgumentException 并点名目录. */
  public AgentDefinition load(Path agentDir) {
    if (agentDir == null || !Files.isDirectory(agentDir)) {
      throw new IllegalArgumentException("Agent 目录不存在: " + agentDir);
    }
    Path mainFile = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(mainFile)) {
      throw new IllegalArgumentException("Agent 目录缺少主文件 AGENT.md: " + agentDir);
    }
    String text;
    try {
      text = Files.readString(mainFile);
    } catch (IOException e) {
      throw new IllegalArgumentException("AGENT.md 读取失败: " + agentDir, e);
    }
    String[] lines = text.split("\r?\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].strip())) {
      throw new IllegalArgumentException("AGENT.md 缺少 frontmatter 围栏(---): " + agentDir);
    }
    int close = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].strip())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      throw new IllegalArgumentException("AGENT.md 缺少 frontmatter 闭合围栏(---): " + agentDir);
    }
    Object raw;
    try {
      raw = new Yaml().load(String.join("\n", Arrays.copyOfRange(lines, 1, close)));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("AGENT.md frontmatter 解析失败: " + agentDir, e);
    }
    if (raw != null && !(raw instanceof Map)) {
      throw new IllegalArgumentException("AGENT.md frontmatter 不是键值块: " + agentDir);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> frontmatter =
        raw == null ? Map.of() : (Map<String, Object>) resolveAndNormalize(raw);
    String body =
        close + 1 >= lines.length
            ? ""
            : String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
    Path scriptsDir = agentDir.resolve("scripts");
    Path skillsDir = agentDir.resolve("skills");
    Path referenceFile = agentDir.resolve("REFERENCE.md");
    return new AgentDefinition(
        agentDir,
        frontmatter,
        body,
        Files.isDirectory(scriptsDir) ? scriptsDir : null,
        Files.isDirectory(skillsDir) ? skillsDir : null,
        Files.isRegularFile(referenceFile) ? referenceFile : null);
  }

  /**
   * 派生入口:把 Agent 目录映射成与手写 YAML 同构的 Profile,frontmatter 各键一一对应. 正文经既有 {@code identity.promptFile}
   * 绑定目录主文件(每次触发由 ContextLoader 现读注入,改正文不重启即生效)。
   */
  public Profile deriveProfile(Path agentDir) {
    AgentDefinition definition = load(agentDir);
    Map<String, Object> map = new LinkedHashMap<>(definition.frontmatter());
    Map<String, Object> identity = new LinkedHashMap<>();
    if (map.get(IDENTITY_KEY) instanceof Map<?, ?> identityNode) {
      for (Map.Entry<?, ?> entry : identityNode.entrySet()) {
        identity.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    identity.put(PROMPT_FILE_KEY, "agents/" + agentDir.getFileName() + "/" + AGENT_FILE);
    map.put(IDENTITY_KEY, identity);
    return mapper.convertValue(map, Profile.class);
  }

  // 以下三个方法逐字对齐 ProfileLoader 的归一化/解析规则(同一作者契约,两处必须同步演进)。

  /** 递归解析 {@code ${ENV_VAR}} 占位符,并把 snake_case 的 map 键转为 camelCase. 变量未设置的占位符保持原样. */
  private Object resolveAndNormalize(Object node) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(toCamelCase(String.valueOf(entry.getKey())), resolveAndNormalize(entry.getValue()));
      }
      return out;
    }
    if (node instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(resolveAndNormalize(item));
      }
      return out;
    }
    if (node instanceof String text) {
      return resolveEnv(text);
    }
    return node;
  }

  private static String resolveEnv(String text) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      matcher.appendReplacement(
          result, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String toCamelCase(String snakeCaseKey) {
    StringBuilder result = new StringBuilder();
    for (String segment : snakeCaseKey.split(KEY_SEGMENT_SEPARATOR)) {
      if (segment.isEmpty()) {
        continue;
      }
      if (result.length() == 0) {
        result.append(segment);
      } else {
        result.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
      }
    }
    return result.toString();
  }
}
