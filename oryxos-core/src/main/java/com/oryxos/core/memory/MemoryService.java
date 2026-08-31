package com.oryxos.core.memory;

import com.oryxos.core.session.Session;
import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 引擎访问三层记忆的唯一端口,使core不依赖具体存储实现.
 *
 * @author OryxOS Contributors
 */
public interface MemoryService {

  /**
   * 为本轮推理提供长期记忆和截断后的会话历史.
   *
   * @param session 当前会话
   * @param maxHistoryTurns 最大历史消息数
   * @return 长期记忆消息加最近会话消息
   */
  List<Message> buildContext(Session session, int maxHistoryTurns);

  /**
   * 把一条长期记忆写入指定分区.
   *
   * @param content 待保存内容
   * @param scope 核心或归档分区
   */
  void remember(String content, MemoryScope scope);

  /**
   * 仅检索有效归档,不混入核心或历史副本;检索语义由选定后端定义.
   *
   * @param keyword 检索文本
   * @return 按后端稳定规则排列的命中内容
   */
  List<String> recall(String keyword);
}
