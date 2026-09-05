package com.oryxos.web.api;

import com.oryxos.provider.ProviderProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态端点:health 回固定 ok;info 带版本与各 provider 连通状态(口径:启动时是否成功 注册进显式映射表,不做主动探活).
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class SystemApiController {

  private final String appName;

  private final String version;

  private final String description;

  private final ObjectProvider<Set<String>> globalProviderNames;

  private final ProviderProperties providerProperties;

  /** 以应用元数据与 provider 声明/注册两面创建 Controller. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的是 Spring 容器单例 Bean,引用共享是依赖注入的本义。")
  public SystemApiController(
      @Value("${info.app.name:OryxOS}") String appName,
      @Value("${info.app.version:unknown}") String version,
      @Value("${info.app.description:}") String description,
      ObjectProvider<Set<String>> globalProviderNames,
      ProviderProperties providerProperties) {
    this.appName = appName;
    this.version = version;
    this.description = description;
    this.globalProviderNames = globalProviderNames;
    this.providerProperties = providerProperties;
  }

  /** 存活检查:进程能答就算活着(深度探活归 actuator,不在此端点). */
  @GetMapping("/health")
  public ApiResponse<HealthResponse> health() {
    return ApiResponse.success(new HealthResponse("ok"));
  }

  /** 版本与 provider 连通状态:已声明但凭据缺失被跳过的标 unavailable. */
  @GetMapping("/info")
  public ApiResponse<InfoResponse> info() {
    Set<String> registered = globalProviderNames.getIfAvailable(Set::of);
    List<ProviderStatus> providers =
        providerProperties.getProviders().stream()
            .map(
                entry ->
                    new ProviderStatus(
                        entry.getName(),
                        registered.contains(entry.getName()) ? "registered" : "unavailable"))
            .toList();
    return ApiResponse.success(new InfoResponse(appName, version, description, providers));
  }

  /** 存活载荷. */
  public record HealthResponse(String status) {}

  /** 系统信息载荷. */
  public record InfoResponse(
      String name, String version, String description, List<ProviderStatus> providers) {

    /** 防御性复制,保持信封不可变. */
    public InfoResponse {
      providers = providers == null ? List.of() : List.copyOf(providers);
    }
  }

  /** 单个 provider 的连通状态(registered/unavailable). */
  public record ProviderStatus(String name, String status) {}
}
