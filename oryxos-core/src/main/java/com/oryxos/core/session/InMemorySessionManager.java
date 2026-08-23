package com.oryxos.core.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 核心循环阶段的内存版 {@link SessionManager}. 后续 CLI 课会用 SQLite 实现替换它,调用方 不受影响。
 *
 * @author OryxOS Contributors
 */
public class InMemorySessionManager implements SessionManager {

  private final Map<String, Session> sessions = new LinkedHashMap<>();

  /** 按 id 存会话,覆盖此前的快照. */
  @Override
  public void save(Session session) {
    sessions.put(session.id(), session);
  }
}
