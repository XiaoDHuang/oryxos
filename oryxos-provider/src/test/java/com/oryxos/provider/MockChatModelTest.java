package com.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class MockChatModelTest {

  private final MockChatModel model = new MockChatModel();

  @Test
  @DisplayName("记住消息只产出一次save_memory意图_JSON原文不变")
  void saveIntentPreservesEscapedInput() throws Exception {
    String fact = "北京的\"咖啡\"店\n路径 C:\\demo";
    var response = model.call(new Prompt(new UserMessage("记住：" + fact)));
    var calls = response.getResult().getOutput().getToolCalls();
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().name()).isEqualTo("save_memory");
    var arguments = new ObjectMapper().readTree(calls.getFirst().arguments());
    assertThat(arguments.path("content").asText()).isEqualTo(fact);
    assertThat(arguments.path("scope").asText()).isEqualTo("archival");
    assertThat(response.getMetadata().getModel()).isEqualTo("mock-script");
    assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(2);
  }

  @Test
  @DisplayName("工具结果返回后结束本轮_失败结果不伪装成功")
  void toolResultEndsTurnWithoutReplay() {
    for (String result : List.of("已记住", "ERROR: 合成工具失败")) {
      var response = model.call(new Prompt(toolResult(result)));
      assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
      assertThat(response.getResult().getOutput().getText()).contains(result).contains("模拟");
    }
  }

  @Test
  @DisplayName("新用户轮次不受旧工具结果污染_不同调用互不共享计数")
  void latestUserStartsNewSave() {
    var response =
        model.call(
            new Prompt(
                List.of(
                    new UserMessage("记住：旧事实"),
                    toolResult("已记住"),
                    new AssistantMessage("完成"),
                    new UserMessage("记住：新事实"))));
    assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
    assertThat(response.getResult().getOutput().getToolCalls().getFirst().arguments())
        .contains("新事实")
        .doesNotContain("旧事实");
  }

  @Test
  @DisplayName("普通提问只回显已注入记忆以验证接线_不触发写入")
  void nonSaveQuestionUsesInjectedMemory() {
    var response =
        model.call(
            new Prompt(List.of(new SystemMessage("## 核心记忆\n住在北京"), new UserMessage("我在哪个城市"))));
    assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
    assertThat(response.getResult().getOutput().getText()).contains("北京").contains("模拟");
  }

  @Test
  @DisplayName("mock显式配置无需key_真实Provider缺凭证仍不注册")
  void explicitMockRegistrationNeedsNoCredential() {
    ProviderProperties properties = new ProviderProperties();
    ProviderProperties.ProviderEntry mock = new ProviderProperties.ProviderEntry();
    mock.setName("mock");
    ProviderProperties.ProviderEntry missing = new ProviderProperties.ProviderEntry();
    missing.setName("deepseek");
    properties.setProviders(List.of(mock, missing));
    var registry = new ProviderConfiguration().chatModelRegistry(properties);
    assertThat(registry).containsOnlyKeys("mock");
    assertThat(registry.get("mock")).isInstanceOf(MockChatModel.class);
    assertThat(new ProviderConfiguration().chatModelRegistry(new ProviderProperties())).isEmpty();
  }

  private static ToolResponseMessage toolResult(String value) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "save_memory", value)))
        .build();
  }
}
