package com.oryxos.storage.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * SQLite 落地的 {@link SessionManager}. 三元组拼接会话 id 只发生在这里——所有入口(CLI 传 "cli"、Web 传 "web"、定时传
 * "scheduler")只提供三元组,不自己拼字符串。历史以 [{role, content, toolCalls?}] 整列 JSON 存取,核心阶段不按条拆表。
 *
 * @author OryxOS Contributors
 */
@Component
public class JpaSessionManager implements SessionManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(JpaSessionManager.class);

  private final SessionRepository repository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 创建基于 sessions 表的管理器. */
  public JpaSessionManager(SessionRepository repository) {
    this.repository = repository;
  }

  /** 同三元组幂等:库里有则回读重建,没有则新建 active 会话立即落库. */
  @Override
  public Session getOrCreate(String channel, String user, String profileName) {
    String sessionId = composeId(channel, user, profileName);
    Optional<SessionEntity> existing = repository.findById(sessionId);
    if (existing.isPresent()) {
      return toRuntime(existing.get());
    }
    String now = Instant.now().toString();
    repository.save(
        new SessionEntity(
            sessionId, profileName, channel, user, "[]", null, "active", now, now, null));
    LOGGER.info("新建会话 {}", sanitize(sessionId));
    return new Session(sessionId, profileName);
  }

  /** 按会话标识回读. */
  @Override
  public Optional<Session> get(String sessionId) {
    return repository.findById(sessionId).map(this::toRuntime);
  }

  /** 持久化累积的消息历史(整列覆盖),并刷新最后活跃时间. */
  @Override
  public void save(Session session) {
    String messagesJson = serialize(session.messages());
    Optional<SessionEntity> existing = repository.findById(session.id());
    if (existing.isPresent()) {
      SessionEntity entity = existing.get();
      entity.updateHistory(messagesJson, Instant.now().toString());
      repository.save(entity);
      return;
    }
    String now = Instant.now().toString();
    repository.save(
        new SessionEntity(
            session.id(),
            session.profileName(),
            null,
            null,
            messagesJson,
            null,
            "active",
            now,
            now,
            null));
  }

  /**
   * 会话 id 的唯一拼接点(H4④):channel + ":" + user + ":" + profile. 分量禁含冒号—— 否则 (a:b,c,d) 与 (a,b:c,d)
   * 会拼出同一个 id,幂等性形同虚设。
   */
  private static String composeId(String channel, String user, String profileName) {
    for (String component : new String[] {channel, user, profileName}) {
      if (component == null || component.isBlank() || component.contains(":")) {
        throw new IllegalArgumentException(
            "会话三元组分量不能为空或含冒号: channel=" + channel + ", user=" + user + ", profile=" + profileName);
      }
    }
    return channel + ":" + user + ":" + profileName;
  }

  private Session toRuntime(SessionEntity entity) {
    Session session = new Session(entity.getSessionId(), entity.getProfileName());
    for (Message message : deserialize(entity.getMessagesJson())) {
      appendByRole(session, message);
    }
    return session;
  }

  private static void appendByRole(Session session, Message message) {
    if (message instanceof UserMessage userMessage) {
      session.append(userMessage);
    } else if (message instanceof AssistantMessage assistantMessage) {
      session.append(assistantMessage);
    } else if (message instanceof ToolResponseMessage toolResponseMessage) {
      session.appendToolResult(toolResponseMessage);
    }
  }

  @SuppressWarnings("unchecked")
  private String serialize(List<Message> messages) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Message message : messages) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("role", message.getMessageType().getValue());
      entry.put("content", message.getText());
      if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
        entry.put("toolCalls", toToolCallEntries(assistant.getToolCalls()));
      }
      if (message instanceof ToolResponseMessage toolMessage) {
        ToolResponseMessage.ToolResponse response = toolMessage.getResponses().get(0);
        // getText() 对工具消息不回数据,内容必须取 ToolResponse 的 responseData
        entry.put("content", response.responseData());
        entry.put("id", response.id());
        entry.put("name", response.name());
      }
      entries.add(entry);
    }
    try {
      return objectMapper.writeValueAsString(entries);
    } catch (JsonProcessingException | RuntimeException e) {
      throw new IllegalStateException("会话历史序列化失败", e);
    }
  }

  private List<Message> deserialize(String messagesJson) {
    if (messagesJson == null || messagesJson.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> entries = objectMapper.readValue(messagesJson, List.class);
      List<Message> messages = new ArrayList<>();
      for (Map<String, Object> entry : entries) {
        messages.add(fromEntry(entry));
      }
      return messages;
    } catch (JsonProcessingException | RuntimeException e) {
      throw new IllegalStateException("会话历史反序列化失败", e);
    }
  }

  private static final String ROLE_USER = "user";

  private static final String ROLE_ASSISTANT = "assistant";

  private static final String ROLE_TOOL = "tool";

  @SuppressWarnings("unchecked")
  private static Message fromEntry(Map<String, Object> entry) {
    String role = String.valueOf(entry.get("role"));
    String content = (String) entry.get("content");
    if (ROLE_ASSISTANT.equals(role)) {
      AssistantMessage.Builder builder = AssistantMessage.builder().content(content);
      Object toolCalls = entry.get("toolCalls");
      if (toolCalls instanceof List<?> list && !list.isEmpty()) {
        builder.toolCalls(
            list.stream()
                .map(item -> (Map<String, Object>) item)
                .map(
                    item ->
                        new AssistantMessage.ToolCall(
                            (String) item.get("id"),
                            (String) item.get("type"),
                            (String) item.get("name"),
                            (String) item.get("arguments")))
                .toList());
      }
      return builder.build();
    }
    if (ROLE_TOOL.equals(role)) {
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(
                      (String) entry.get("id"), (String) entry.get("name"), content)))
          .build();
    }
    if (ROLE_USER.equals(role)) {
      return new UserMessage(content);
    }
    // system 消息按设计不进 Session(PromptBuilder 每轮现拼);遇到未知角色必须响亮失败,
    // 静默吞成 UserMessage 会把脏数据伪装成正常历史。
    throw new IllegalStateException("会话历史含未知角色: " + role);
  }

  private static List<Map<String, Object>> toToolCallEntries(
      List<AssistantMessage.ToolCall> toolCalls) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (AssistantMessage.ToolCall call : toolCalls) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", call.id());
      item.put("type", call.type());
      item.put("name", call.name());
      item.put("arguments", call.arguments());
      entries.add(item);
    }
    return entries;
  }

  /** 外部来源的值进入日志行前,先剥掉 CR/LF. */
  private static String sanitize(Object value) {
    return String.valueOf(value).replace('\r', '_').replace('\n', '_');
  }
}
