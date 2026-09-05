package com.oryxos.web.api;

import com.oryxos.memory.LongTermMemoryStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆只读查询端点:返回启动时唯一选定后端的全量视图(核心+归档窗口), 语义与引擎注入一致;不提供任何写入入口(Memory 写入端点是扩展阶段).
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/memory")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class MemoryApiController {

  private final LongTermMemoryStore store;

  private final String backend;

  /** 以当前选定的记忆存储与后端名创建只读 Controller. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的是 Spring 容器单例 Bean,引用共享是依赖注入的本义。")
  public MemoryApiController(
      LongTermMemoryStore store, @Value("${memory.backend:markdown}") String backend) {
    this.store = store;
    this.backend = backend;
  }

  /** 返回当前后端的全量记忆视图;读取失败按后端契约向上抛,不伪装为空. */
  @GetMapping
  public ApiResponse<MemoryResponse> view() {
    return ApiResponse.success(new MemoryResponse(backend, store.load()));
  }

  /** 记忆视图载荷. */
  public record MemoryResponse(String backend, String content) {}
}
