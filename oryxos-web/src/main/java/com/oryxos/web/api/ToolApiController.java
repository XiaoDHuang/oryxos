package com.oryxos.web.api;

import com.oryxos.tool.ToolRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool 只读查询端点:列注册表快照,不触发任何工具 IO.
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/tools")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class ToolApiController {

  private final ToolRegistry toolRegistry;

  /** 以工具注册表创建只读 Controller. */
  public ToolApiController(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  /** 列出全部已注册 Tool 的名称与描述. */
  @GetMapping
  public ApiResponse<List<ToolSummaryResponse>> list() {
    return ApiResponse.success(
        toolRegistry.all().stream()
            .map(tool -> new ToolSummaryResponse(tool.getName(), tool.getDescription()))
            .toList());
  }

  /** Tool 摘要载荷. */
  public record ToolSummaryResponse(String name, String description) {}
}
