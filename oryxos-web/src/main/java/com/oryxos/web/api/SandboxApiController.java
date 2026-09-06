package com.oryxos.web.api;

import com.oryxos.tool.sandbox.WhitelistSandbox;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱白名单管理端点(增/删/查). 生效语义:内存覆盖——运行期增删立即影响本进程后续工具调用, 重启回 application.yaml 基线(用户决议);增删成功都返回最新的生效名单视图。
 * 注意:核心阶段无认证,本端点即安全边界的钥匙,仅限内网管理面暴露。
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/sandbox/whitelist")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class SandboxApiController {

  private static final String TYPE_FILE = "file";

  private static final String TYPE_SHELL = "shell";

  private static final String TYPE_HTTP = "http";

  private final WhitelistSandbox sandbox;

  /** 以白名单沙箱具体类创建管理 Controller(需要其运行时增删方法). */
  public SandboxApiController(WhitelistSandbox sandbox) {
    this.sandbox = sandbox;
  }

  /** 查询当前生效的三类白名单. */
  @GetMapping
  public ApiResponse<WhitelistView> view() {
    return ApiResponse.success(currentView());
  }

  /** 增加一条白名单条目;幂等(已存在仍返回 200 与最新视图). */
  @PostMapping("/entries")
  public ApiResponse<WhitelistView> add(@RequestBody EntryRequest request) {
    String type = request == null ? null : request.type();
    String value = request == null ? null : request.value();
    requireValid(type, value);
    if (TYPE_FILE.equals(type)) {
      sandbox.allowPath(value);
    } else if (TYPE_SHELL.equals(type)) {
      sandbox.allowCommand(value);
    } else {
      sandbox.allowDomain(value);
    }
    return ApiResponse.success(currentView());
  }

  /** 删除一条白名单条目;条目不在生效名单返回 404. */
  @DeleteMapping("/entries")
  public ApiResponse<WhitelistView> remove(@RequestParam String type, @RequestParam String value) {
    requireValid(type, value);
    boolean removed;
    if (TYPE_FILE.equals(type)) {
      removed = sandbox.denyPath(value);
    } else if (TYPE_SHELL.equals(type)) {
      removed = sandbox.denyCommand(value);
    } else {
      removed = sandbox.denyDomain(value);
    }
    if (!removed) {
      throw new OryxException(ErrorCode.RESOURCE_NOT_FOUND, "条目不在生效名单: " + type + "/" + value);
    }
    return ApiResponse.success(currentView());
  }

  private WhitelistView currentView() {
    return new WhitelistView(
        sandbox.allowedPathView(),
        List.copyOf(sandbox.allowedCommandView()),
        List.copyOf(sandbox.allowedDomainView()));
  }

  private static void requireValid(String type, String value) {
    if (!TYPE_FILE.equals(type) && !TYPE_SHELL.equals(type) && !TYPE_HTTP.equals(type)) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, "type 仅支持 file/shell/http: " + type);
    }
    if (value == null || value.isBlank()) {
      throw new OryxException(ErrorCode.INVALID_REQUEST, "value 不能为空");
    }
  }

  /** 增删条目请求体. */
  public record EntryRequest(String type, String value) {}

  /** 生效名单视图(规范化后的三类条目). */
  public record WhitelistView(
      List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains) {

    /** 防御性复制,保持信封不可变. */
    public WhitelistView {
      allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
      allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
      allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
    }
  }
}
