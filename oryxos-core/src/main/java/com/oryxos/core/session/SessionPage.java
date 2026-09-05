package com.oryxos.core.session;

import java.util.List;

/**
 * 会话列表的一页结果;total 为全量条数,供调用方渲染分页.
 *
 * @author OryxOS Contributors
 */
public record SessionPage(int page, int size, long total, List<SessionSummary> content) {

  /** 校验分页信封的不变字段. */
  public SessionPage {
    content = content == null ? List.of() : List.copyOf(content);
  }
}
