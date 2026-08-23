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
 * 启动时构建显式的 provider 名 → {@link ChatModel} 映射. 对 Spring 上下文做类型扫描 永远分不清 deepseek 和 kimi(bean
 * 类型相同、bean 名不可靠),所以映射要逐条写死 —— 这正是全部意义所在。凭据占位符解析为空的已声明 provider 会被报告并跳过,不阻塞 其余 provider。
 *
 * @author OryxOS Contributors
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProviderConfiguration.class);

  /** 创建供 {@link ProviderService} 消费的显式「名称 → 模型」注册表. */
  @Bean
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "provider 名在写入日志前做了 CR/LF 清洗;没有其他用户可控内容进入这些日志行。")
  public Map<String, ChatModel> chatModelRegistry(ProviderProperties properties) {
    Map<String, ChatModel> registry = new LinkedHashMap<>();
    for (ProviderProperties.ProviderEntry entry : properties.getProviders()) {
      if (entry.getName() == null || entry.getName().isBlank()) {
        LOGGER.error("跳过 oryxos.providers 中缺少 name 的 provider 条目");
        continue;
      }
      if (entry.getApiKey() == null || entry.getApiKey().isBlank()) {
        LOGGER.error(
            "Provider '{}' 被跳过:凭据缺失 —— 请设置其 api-key 在 oryxos.providers 中引用的环境变量",
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
    LOGGER.info("已注册 {} 个 LLM provider: {}", registry.size(), registry.keySet());
    return Map.copyOf(registry);
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
