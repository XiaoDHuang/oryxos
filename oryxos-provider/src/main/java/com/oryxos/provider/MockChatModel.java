package com.oryxos.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 显式配置的离线模型用于可重演的链路对账,不代表真实推理.
 *
 * @author OryxOS Contributors
 */
public final class MockChatModel implements ChatModel {

  private static final String SAVE_PREFIX = "记住：";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Message> messages = prompt.getInstructions();
    if (messages.isEmpty()) {
      throw new IllegalArgumentException("模拟模型需要消息");
    }
    Message latest = messages.getLast();
    AssistantMessage answer;
    if (latest instanceof ToolResponseMessage result) {
      String text =
          result.getResponses().stream()
              .map(ToolResponseMessage.ToolResponse::responseData)
              .collect(Collectors.joining("\n"));
      answer = new AssistantMessage("模拟模型收到工具结果：" + text);
    } else if (latest instanceof UserMessage user && user.getText().startsWith(SAVE_PREFIX)) {
      String fact = user.getText().substring(SAVE_PREFIX.length());
      if (fact.isBlank()) {
        throw new IllegalArgumentException("模拟保存的内容不能为空");
      }
      answer =
          AssistantMessage.builder()
              .content("模拟模型请求保存记忆")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall(
                          UUID.randomUUID().toString(),
                          "function",
                          "save_memory",
                          arguments(fact))))
              .build();
    } else {
      // 回显已注入的上下文只证明接线可见性,不把它当成语义推理或真实召回效果。
      String context =
          prompt.getSystemMessages().stream()
              .map(Message::getText)
              .collect(Collectors.joining("\n"));
      answer = new AssistantMessage("模拟回答（仅用于离线链路验证）：\n" + context);
    }
    // 固定合成 usage 让审计有可断言的值,model 标记避免被误认为真实计费统计。
    return new ChatResponse(
        List.of(new Generation(answer)),
        ChatResponseMetadata.builder().model("mock-script").usage(new DefaultUsage(1, 1)).build());
  }

  private static String arguments(String content) {
    try {
      return MAPPER.writeValueAsString(Map.of("content", content, "scope", "archival"));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("模拟工具参数序列化失败", exception);
    }
  }
}
