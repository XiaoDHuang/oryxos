package com.oryxos.web.api;

import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.react.AgentService;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import com.oryxos.web.api.SessionApiController.MessageResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无状态一次性调用端点. 每次调用用带 UUID 的三元组拿全新会话(id 拼接仍只在 SessionManager 内部), 跑完无论成败都归档,invoke 不往会话列表留活跃残留;复用
 * sessions 端点的 MessageResponse 契约与 32KB 防呆。
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/agents")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class AgentApiController {

  private static final int MAX_MESSAGE_CHARS = 32 * 1024;

  private static final String CHANNEL = "invoke";

  private final AgentService agentService;

  private final SessionManager sessionManager;

  private final ProfileRegistry profileRegistry;

  /** 以引擎入口、会话端口与 profile 索引创建薄 Controller. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者是 Spring 容器管理的单例 Bean,Controller 的本职就是持有并驱动它们,防御性拷贝反而语义错误。")
  public AgentApiController(
      AgentService agentService, SessionManager sessionManager, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
  }

  /** 一次性调用:校验同步做完,Callable 体内只剩建会话、跑引擎、归档三步. */
  @PostMapping("/{name}/invoke")
  public Callable<ApiResponse<MessageResponse>> invoke(
      @PathVariable String name, @RequestBody InvokeRequest request) {
    String content = request == null ? null : request.content();
    if (content == null || content.isBlank() || content.length() > MAX_MESSAGE_CHARS) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, "消息为空或超过 32KB");
    }
    if (profileRegistry.find(name).isEmpty()) {
      throw new OryxException(ErrorCode.RESOURCE_NOT_FOUND, "Agent 未注册: " + name);
    }
    return () -> {
      Session session =
          sessionManager.getOrCreate(CHANNEL, CHANNEL + "-" + UUID.randomUUID(), name);
      try {
        return ApiResponse.success(new MessageResponse(agentService.process(session, content)));
      } finally {
        sessionManager.archive(session.id());
      }
    };
  }

  /** 一次性调用请求体(校验规则与 MessageRequest 相同). */
  public record InvokeRequest(String content) {}
}
