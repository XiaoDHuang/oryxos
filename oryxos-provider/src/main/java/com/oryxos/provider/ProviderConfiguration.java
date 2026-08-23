package com.oryxos.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the explicit provider-name → {@link ChatModel} map at startup. Type-scanning the Spring
 * context can never tell deepseek from kimi (same bean type, unreliable bean names), so the map is
 * written down entry by entry — that is the whole point. A declared provider whose credential
 * placeholder resolved to empty is reported and skipped without blocking the rest.
 *
 * @author OryxOS Contributors
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProviderConfiguration.class);

  /** Creates the explicit name → model registry consumed by {@link ProviderService}. */
  @Bean
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Provider names are CR/LF-sanitized before logging; nothing else user-controlled enters"
              + " these log lines.")
  public Map<String, ChatModel> chatModelRegistry(ProviderProperties properties) {
    Map<String, ChatModel> registry = new LinkedHashMap<>();
    for (ProviderProperties.ProviderEntry entry : properties.getProviders()) {
      if (entry.getName() == null || entry.getName().isBlank()) {
        LOGGER.error("Skipping provider entry with missing name in oryxos.providers");
        continue;
      }
      if (entry.getApiKey() == null || entry.getApiKey().isBlank()) {
        LOGGER.error(
            "Provider '{}' skipped: credential missing — set the environment variable referenced"
                + " by its api-key in oryxos.providers",
            sanitize(entry.getName()));
        continue;
      }
      OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(entry.getApiKey());
      if (entry.getBaseUrl() != null && !entry.getBaseUrl().isBlank()) {
        apiBuilder.baseUrl(entry.getBaseUrl());
      }
      registry.put(
          entry.getName(), OpenAiChatModel.builder().openAiApi(apiBuilder.build()).build());
    }
    LOGGER.info("Registered {} LLM provider(s): {}", registry.size(), registry.keySet());
    return Map.copyOf(registry);
  }

  /** Strips CR/LF from externally-sourced values before they enter log lines. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
