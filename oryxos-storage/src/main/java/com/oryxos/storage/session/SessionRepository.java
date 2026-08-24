package com.oryxos.storage.session;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话表的 Spring Data 访问入口(sessions 表). 核心阶段:按主键存取;列表/归档查询由 Web 课(26 节)补。
 *
 * @author OryxOS Contributors
 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {}
