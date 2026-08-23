package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.prompt.Prompt;
import com.oryxos.storage.audit.LlmCallRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.util.StringUtils;

/**
 * 真实网络冒烟:证明配置的 key、解析出的依赖与一次真实调用端到端可用,且成功审计确实 落进 llm_calls. 打上 {@code integration} 标签,使 CI 从不依赖外部
 * API 的可用性; 真实 key 就位后按 quickstart.md 手动运行。
 *
 * @author OryxOS Contributors
 */
@Tag("integration")
@SpringBootTest(
    classes = ProviderSmokeIT.SmokeConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:sqlite:target/smoke-provider.db",
      "spring.datasource.driver-class-name=org.sqlite.JDBC",
      "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:db/schema.sql"
    })
class ProviderSmokeIT {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("com.oryxos.storage.audit")
  @EnableJpaRepositories("com.oryxos.storage.audit")
  static class SmokeConfig {

    @Bean
    ToolSchemaAdapter toolSchemaAdapter() {
      return new ToolSchemaAdapter();
    }

    @Bean
    LlmCallAudit llmCallAudit(LlmCallRepository repository) {
      return new LlmCallAudit(repository);
    }

    @Bean
    ProviderService providerService(ToolSchemaAdapter adapter, LlmCallAudit audit) {
      String apiKey = System.getenv("DEEPSEEK_API_KEY");
      String baseUrl = System.getenv("DEEPSEEK_BASE_URL");
      OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey == null ? "" : apiKey);
      if (StringUtils.hasText(baseUrl)) {
        apiBuilder.baseUrl(baseUrl);
      }
      ChatModel deepseek = OpenAiChatModel.builder().openAiApi(apiBuilder.build()).build();
      return new ProviderService(Map.of("deepseek", deepseek), adapter, audit);
    }
  }

  @Autowired private ProviderService providerService;

  @Autowired private LlmCallRepository repository;

  @Test
  @DisplayName("集成冒烟_真调一次模型且llm_calls多一条success为true")
  void realCall_persistsSuccessAudit() {
    Assumptions.assumeTrue(
        StringUtils.hasText(System.getenv("DEEPSEEK_API_KEY")), "未设置 DEEPSEEK_API_KEY");
    long before = repository.count();

    ChatResponse response =
        providerService.chat(
            "smoke-1",
            new Profile(
                "smoke",
                null,
                null,
                new Profile.Provider("deepseek", "deepseek-chat", 0.7, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            new Prompt(List.of(new UserMessage("Reply with exactly: OK")), List.of()));

    assertThat(response.getResult().getOutput().getText()).isNotBlank();
    assertThat(repository.count()).isEqualTo(before + 1);
    assertThat(repository.findAll().stream().filter(call -> "smoke-1".equals(call.getSessionId())))
        .allMatch(call -> Boolean.TRUE.equals(call.getSuccess()));
  }
}
