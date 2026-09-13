package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import com.oryxos.core.session.Session;
import com.oryxos.core.session.SessionManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * 三类触发来源(CLI / Web / 调度器)共享的唯一入口. 编排一个处理回合:把当前 Profile 植入请求级的 {@link ProfileContext}(工具从那里读取 ——
 * 它们的签名刻意不携带 Profile)、跑循环、持久化累积的会话,并在 finally 中必定清空上下文,使被复用的虚拟 线程绝不会把一个 agent 的 Profile 泄漏进另一个请求。
 *
 * @author OryxOS Contributors
 */
public class AgentService {

  private final ReActLoop reActLoop;

  private final ProfileRegistry profileRegistry;

  private final SessionManager sessionManager;

  /** 以循环、profile 索引与会话存储创建编排器. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者是 Spring 容器管理的单例 Bean,编排器的本职就是持有并驱动它们,防御性拷贝反而语义错误。")
  public AgentService(
      ReActLoop reActLoop, ProfileRegistry profileRegistry, SessionManager sessionManager) {
    this.reActLoop = reActLoop;
    this.profileRegistry = profileRegistry;
    this.sessionManager = sessionManager;
  }

  /** 在给定会话内处理一条用户消息,返回最终回复. */
  public String process(Session session, String userMessage) {
    Profile profile =
        profileRegistry
            .find(session.profileName())
            .orElseThrow(() -> new IllegalStateException("Profile 未注册: " + session.profileName()));
    ProfileContext.set(profile);
    try {
      String reply = reActLoop.run(session, userMessage, profile);
      sessionManager.save(session);
      return reply;
    } finally {
      ProfileContext.clear();
    }
  }
}
