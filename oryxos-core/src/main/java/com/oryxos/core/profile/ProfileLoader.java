package com.oryxos.core.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 启动时加载 {@code .oryxos/profiles/} 下的全部 Profile YAML. 损坏的文件记日志并跳过, 不阻塞其余文件;{@code provider.name}
 * 不在全局 provider 层中的 Profile 会被报告并跳过(本节拥有的唯一校验规则)。
 *
 * @author OryxOS Contributors
 */
public class ProfileLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProfileLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final ObjectMapper mapper = new ObjectMapper();

  private final ProfileValidator validator;

  /** 创建一个按给定全局 provider 名集合校验 profile 的加载器. */
  public ProfileLoader(Set<String> globalProviderNames) {
    this.validator = new ProfileValidator(globalProviderNames);
  }

  /** 加载并校验目录下的全部 profile;文件损坏不抛异常. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "所有外部来源的值在写入日志前都做了 CR/LF 清洗;被标记的参数是末尾的 Throwable," + "由 SLF4J 渲染为堆栈,而不是单行文本。")
  public List<Profile> loadAll(Path profilesDir) {
    if (profilesDir == null || !Files.isDirectory(profilesDir)) {
      LOGGER.warn("Profiles 目录 {} 不存在;未加载任何 profile", sanitize(profilesDir));
      return List.of();
    }
    List<Path> files;
    try (Stream<Path> stream = Files.list(profilesDir)) {
      files =
          stream.filter(Files::isRegularFile).filter(ProfileLoader::isYamlFile).sorted().toList();
    } catch (IOException e) {
      LOGGER.error("列出 profiles 目录 {} 失败: {}", sanitize(profilesDir), sanitize(e.getMessage()), e);
      return List.of();
    }
    List<Profile> profiles = new ArrayList<>();
    for (Path file : files) {
      try {
        loadOne(file).ifPresent(profiles::add);
      } catch (IOException | RuntimeException e) {
        LOGGER.error("跳过损坏的 profile 文件 {}: {}", sanitize(file), sanitize(e.getMessage()), e);
      }
    }
    LOGGER.info("已从 {} 加载 {} 个 profile", profiles.size(), sanitize(profilesDir));
    return List.copyOf(profiles);
  }

  private Optional<Profile> loadOne(Path file) throws IOException {
    Object raw;
    try (var reader = Files.newBufferedReader(file)) {
      raw = new Yaml().load(reader);
    }
    if (raw == null) {
      LOGGER.error("跳过空的 profile 文件 {}", sanitize(file));
      return Optional.empty();
    }
    Object normalized = resolveAndNormalize(raw);
    Profile profile = mapper.convertValue(normalized, Profile.class);
    try {
      // 与运行时注册同一套校验;此处 catch 后按既有格式记日志并跳过,异常消息逐字保留
      validator.validate(profile);
    } catch (IllegalArgumentException e) {
      LOGGER.error("跳过 profile 文件 {}: {}", sanitize(file), sanitize(e.getMessage()));
      return Optional.empty();
    }
    return Optional.of(profile);
  }

  /**
   * 递归地对进程环境解析 {@code ${ENV_VAR}} 占位符,并把 snake_case 的 map 键转为 camelCase,使 SnakeYAML 产出的 map 能映射到
   * record 上. 变量未设置的占位符保持原样 (不发明新语义)。
   */
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

  private static final String KEY_SEGMENT_SEPARATOR = "_";

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
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

  private static boolean isYamlFile(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString();
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }
}
