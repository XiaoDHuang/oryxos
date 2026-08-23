package com.oryxos.core.session;

/**
 * 会话生命周期边界. 本节只交付 {@link #save(Session)}(由 AgentService 在每个处理回合 后调用);查找/创建({@code
 * getOrCreate(channel, user, profileName)},会话 id 的唯一 组装地点)随 CLI 课到来。
 *
 * @author OryxOS Contributors
 */
public interface SessionManager {

  /**
   * 在一个处理回合后持久化会话累积的历史.
   *
   * @param session 要保存的会话
   */
  void save(Session session);
}
