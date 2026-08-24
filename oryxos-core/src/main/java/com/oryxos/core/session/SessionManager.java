package com.oryxos.core.session;

import java.util.Optional;

/**
 * 会话生命周期边界. 三个方法:getOrCreate 按 channel+user+profile 三元组幂等取会话(id 的拼接
 * 只发生在实现内部——所有入口只提供三元组,不自己拼字符串,否则两处各拼一遍、格式差一个分隔符, 同一个人就会出现两条互不相认的历史);get 按 id 回读;save
 * 在一轮处理结束后持久化累积的历史。
 *
 * @author OryxOS Contributors
 */
public interface SessionManager {

  /**
   * 按三元组取已有会话或新建一个,幂等.
   *
   * @param channel 接入通道(cli / web / scheduler)
   * @param user 用户标识
   * @param profileName Profile 名
   * @return 该三元组唯一对应的会话
   */
  Session getOrCreate(String channel, String user, String profileName);

  /**
   * 按会话标识回读.
   *
   * @param sessionId 会话标识
   * @return 存在则返回,否则空
   */
  Optional<Session> get(String sessionId);

  /**
   * 一轮处理结束后持久化会话累积的历史.
   *
   * @param session 待保存的会话
   */
  void save(Session session);
}
