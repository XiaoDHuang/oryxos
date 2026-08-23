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
 * Loads every Profile YAML under {@code .oryxos/profiles/} at startup. Broken files are logged and
 * skipped without blocking the rest; a Profile whose {@code provider.name} is absent from the
 * global provider layer is reported and skipped (the single validation rule owned by this lesson).
 *
 * @author OryxOS Contributors
 */
public class ProfileLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProfileLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final Set<String> globalProviderNames;

  private final ObjectMapper mapper = new ObjectMapper();

  /** Creates a loader that validates profiles against the given global provider names. */
  public ProfileLoader(Set<String> globalProviderNames) {
    this.globalProviderNames =
        globalProviderNames == null ? Set.of() : Set.copyOf(globalProviderNames);
  }

  /** Loads and validates all profiles in the directory; never throws for broken files. */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "All externally-sourced values are CR/LF-sanitized before logging; the flagged argument"
              + " is the trailing Throwable, which SLF4J renders as a stack trace, not a line.")
  public List<Profile> loadAll(Path profilesDir) {
    if (profilesDir == null || !Files.isDirectory(profilesDir)) {
      LOGGER.warn(
          "Profiles directory {} does not exist; no profiles loaded", sanitize(profilesDir));
      return List.of();
    }
    List<Path> files;
    try (Stream<Path> stream = Files.list(profilesDir)) {
      files =
          stream.filter(Files::isRegularFile).filter(ProfileLoader::isYamlFile).sorted().toList();
    } catch (IOException e) {
      LOGGER.error(
          "Failed to list profiles directory {}: {}",
          sanitize(profilesDir),
          sanitize(e.getMessage()),
          e);
      return List.of();
    }
    List<Profile> profiles = new ArrayList<>();
    for (Path file : files) {
      try {
        loadOne(file).ifPresent(profiles::add);
      } catch (IOException | RuntimeException e) {
        LOGGER.error(
            "Skipping broken profile file {}: {}", sanitize(file), sanitize(e.getMessage()), e);
      }
    }
    LOGGER.info("Loaded {} profile(s) from {}", profiles.size(), sanitize(profilesDir));
    return List.copyOf(profiles);
  }

  private Optional<Profile> loadOne(Path file) throws IOException {
    Object raw;
    try (var reader = Files.newBufferedReader(file)) {
      raw = new Yaml().load(reader);
    }
    if (raw == null) {
      LOGGER.error("Skipping empty profile file {}", sanitize(file));
      return Optional.empty();
    }
    Object normalized = resolveAndNormalize(raw);
    Profile profile = mapper.convertValue(normalized, Profile.class);
    if (profile.name() == null || profile.name().isBlank()) {
      LOGGER.error("Skipping profile file {}: missing required field 'name'", sanitize(file));
      return Optional.empty();
    }
    String providerName = profile.provider() == null ? null : profile.provider().name();
    if (providerName == null || !globalProviderNames.contains(providerName)) {
      LOGGER.error(
          "Skipping profile '{}': provider '{}' is not declared in the global provider layer",
          sanitize(profile.name()),
          sanitize(providerName));
      return Optional.empty();
    }
    return Optional.of(profile);
  }

  /**
   * Recursively resolves {@code ${ENV_VAR}} placeholders against the process environment and
   * converts snake_case map keys to camelCase so SnakeYAML-produced maps map onto the record. A
   * placeholder whose variable is unset is left as-is (no new semantics invented).
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

  /** Strips CR/LF from externally-sourced values before they enter log lines. */
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
