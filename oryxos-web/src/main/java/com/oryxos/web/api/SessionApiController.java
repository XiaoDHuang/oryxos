package com.oryxos.web.api;

import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import com.oryxos.core.session.SessionPage;
import com.oryxos.core.session.SessionSummary;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点(创建/列表/发消息/查历史/归档). 薄壳:只做参数校验、响应包装、错误抛出, 引擎调用与 CLI 共用 {@link AgentService#process};调引擎的端点包成
 * Callable, 由容器在 60 秒异步上限处掐断(超时经 GlobalExceptionHandler 映射 504)。
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/sessions")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class SessionApiController {

  /** 单条消息最大 32KB(防呆红线,§7.4). */
  private static final int MAX_MESSAGE_CHARS = 32 * 1024;

  /** 历史查询最多返回最近 100 条(防呆红线,§7.4). */
  private static final int MAX_HISTORY_MESSAGES = 100;

  private static final String CHANNEL = "web";

  private static final String STATUS_ACTIVE = "active";

  private static final String STATUS_ARCHIVED = "archived";

  private final AgentService agentService;

  private final SessionManager sessionManager;

  private final ProfileRegistry profileRegistry;

  /** 以引擎入口、会话端口与 profile 索引创建薄 Controller. */
  public SessionApiController(
      AgentService agentService, SessionManager sessionManager, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
  }

  /** 创建会话:同三元组幂等;Profile 未注册按 404. */
  @PostMapping
  public ApiResponse<SessionSummaryResponse> create(@RequestBody CreateSessionRequest request) {
    String profileName = request == null ? null : request.profileName();
    String userId = request == null ? null : request.userId();
    if (isBlank(profileName) || isBlank(userId)) {
      throw invalid("profileName 与 userId 不能为空");
    }
    if (profileRegistry.find(profileName).isEmpty()) {
      throw notFound("Profile 未注册: " + profileName);
    }
    Session session = sessionManager.getOrCreate(CHANNEL, userId, profileName);
    return ApiResponse.success(
        new SessionSummaryResponse(
            session.id(), session.profileName(), CHANNEL, userId, statusOf(session)));
  }

  /** 分页列出会话摘要(管理台会话列表页的数据源). */
  @GetMapping
  public ApiResponse<SessionPageResponse> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    SessionPage result = sessionManager.listSessions(page, size);
    return ApiResponse.success(
        new SessionPageResponse(result.page(), result.size(), result.total(), result.content()));
  }

  /** 发消息:校验做完才进 Callable,异步体里只剩一次引擎调用. */
  @PostMapping("/{id}/messages")
  public Callable<ApiResponse<MessageResponse>> send(
      @PathVariable String id, @RequestBody MessageRequest request) {
    String content = request == null ? null : request.content();
    if (content == null || content.isBlank() || content.length() > MAX_MESSAGE_CHARS) {
      throw invalid("消息为空或超过 32KB");
    }
    Session session = requireSession(id);
    if (session.archived()) {
      throw invalid("会话已归档");
    }
    return () -> ApiResponse.success(new MessageResponse(agentService.process(session, content)));
  }

  /** 查历史:最多返回最近 100 条,totalMessages 暴露全量条数. */
  @GetMapping("/{id}")
  public ApiResponse<SessionDetailResponse> detail(@PathVariable String id) {
    Session session = requireSession(id);
    List<Message> messages = session.messages();
    int total = messages.size();
    List<Message> tail = messages.subList(Math.max(0, total - MAX_HISTORY_MESSAGES), total);
    return ApiResponse.success(
        new SessionDetailResponse(
            session.id(),
            session.profileName(),
            statusOf(session),
            total,
            tail.stream().map(SessionApiController::toView).toList()));
  }

  /** 归档:关闭写入但历史仍可读;未知会话 404. */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> archive(@PathVariable String id) {
    if (!sessionManager.archive(id)) {
      throw notFound("会话不存在: " + id);
    }
    return ApiResponse.success(null);
  }

  private Session requireSession(String id) {
    return sessionManager.get(id).orElseThrow(() -> notFound("会话不存在: " + id));
  }

  private static String statusOf(Session session) {
    return session.archived() ? STATUS_ARCHIVED : STATUS_ACTIVE;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static OryxException invalid(String message) {
    return new OryxException(ErrorCode.INVALID_REQUEST, message);
  }

  private static OryxException notFound(String message) {
    return new OryxException(ErrorCode.RESOURCE_NOT_FOUND, message);
  }

  /** 工具消息的内容必须取 ToolResponse.responseData(getText 不回数据,与持久层序列化同规则). */
  private static SessionMessageView toView(Message message) {
    String role = message.getMessageType().getValue();
    if (message instanceof ToolResponseMessage toolMessage) {
      ToolResponseMessage.ToolResponse response = toolMessage.getResponses().get(0);
      return new SessionMessageView(role, response.responseData(), null);
    }
    if (message instanceof AssistantMessage assistant) {
      List<ToolCallView> toolCalls =
          assistant.hasToolCalls()
              ? assistant.getToolCalls().stream()
                  .map(
                      call ->
                          new ToolCallView(call.id(), call.type(), call.name(), call.arguments()))
                  .toList()
              : null;
      return new SessionMessageView(role, assistant.getText(), toolCalls);
    }
    return new SessionMessageView(role, message.getText(), null);
  }

  /** 创建会话请求体. */
  public record CreateSessionRequest(String profileName, String userId) {}

  /** 创建会话响应载荷. */
  public record SessionSummaryResponse(
      String sessionId, String profileName, String channel, String userId, String status) {}

  /** 会话列表分页载荷. */
  public record SessionPageResponse(int page, int size, long total, List<SessionSummary> content) {

    /** 防御性复制,保持信封不可变. */
    public SessionPageResponse {
      content = content == null ? List.of() : List.copyOf(content);
    }
  }

  /** 发消息请求体. */
  public record MessageRequest(String content) {}

  /** 发消息响应载荷(课件既定字面量). */
  public record MessageResponse(String reply) {}

  /** 历史条目视图. */
  public record SessionMessageView(String role, String content, List<ToolCallView> toolCalls) {

    /** 防御性复制;toolCalls 可空(非 assistant 消息无工具调用). */
    public SessionMessageView {
      toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }
  }

  /** 一条工具调用意图的视图. */
  public record ToolCallView(String id, String type, String name, String arguments) {}

  /** 会话详情载荷. */
  public record SessionDetailResponse(
      String sessionId,
      String profileName,
      String status,
      int totalMessages,
      List<SessionMessageView> messages) {

    /** 防御性复制,保持信封不可变. */
    public SessionDetailResponse {
      messages = messages == null ? List.of() : List.copyOf(messages);
    }
  }
}
