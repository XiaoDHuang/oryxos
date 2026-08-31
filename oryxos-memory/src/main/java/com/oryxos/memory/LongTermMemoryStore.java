package com.oryxos.memory;

import com.oryxos.core.memory.MemoryScope;
import java.util.List;

/**
 * 后端差异留在能力模块,避免引擎感知存储与分页协议.
 *
 * @author OryxOS Contributors
 */
public interface LongTermMemoryStore {

  /**
   * 成功意味着持久化且下一轮可读;空白内容拒绝,null范围沿用归档默认值.
   *
   * @param content 保存原文
   * @param scope 核心或归档范围
   */
  void append(String content, MemoryScope scope);

  /**
   * 返回完整核心与后端归档窗口,不生成空段;裁剪不得删除历史,读取失败不得伪装为空.
   *
   * @return 本轮完整记忆视图
   */
  String load();

  /**
   * 只查询有效归档,不混入核心;字面或语义规则由后端定义,空查询和访问失败须抛出异常.
   *
   * @param query 检索文本
   * @return 有效归档命中内容
   */
  List<String> recall(String query);
}
