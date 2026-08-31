package com.oryxos.storage.memory;

import com.oryxos.core.memory.MemoryScope;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 同毫秒以自增编号消歧,字面查询不借用SQL通配语义.
 *
 * @author OryxOS Contributors
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  /**
   * 核心不能被分页窗口遗漏.
   *
   * @param scope 固定分区
   * @return 时间及编号升序的完整分区
   */
  List<MemoryEntry> findByScopeOrderByCreatedAtAscIdAsc(MemoryScope scope);

  /**
   * 只缩小注入视图,保留全部原始行.
   *
   * @param scope 固定分区
   * @return 时间及编号降序的最新一百条
   */
  List<MemoryEntry> findTop100ByScopeOrderByCreatedAtDescIdDesc(MemoryScope scope);

  /**
   * 百分号、下划线和引号均只是查询原文.
   *
   * @param keyword 字面检索文本
   * @return 时间及编号升序的归档命中
   */
  @Query(
      value =
          "SELECT * FROM memory_entries WHERE scope='ARCHIVAL'"
              + " AND instr(content,:keyword)>0 ORDER BY created_at ASC,id ASC",
      nativeQuery = true)
  List<MemoryEntry> recall(@Param("keyword") String keyword);
}
