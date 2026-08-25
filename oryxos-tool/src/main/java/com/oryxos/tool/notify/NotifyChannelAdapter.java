package com.oryxos.tool.notify;

/**
 * 通知渠道的中立发送契约. 调用方只表达目标和内容,渠道专属的 payload 与认证细节留在实现内部。
 *
 * @author OryxOS Contributors
 */
public interface NotifyChannelAdapter {

  /**
   * 向一个通知目标发送内容.
   *
   * @param target 通知目标
   * @param content 待发送内容
   */
  void send(NotifyTarget target, String content);
}
