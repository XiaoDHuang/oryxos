package com.oryxos.web.api;

import com.oryxos.core.profile.Profile;
import com.oryxos.core.profile.ProfileRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile 只读查询端点. 只外发摘要字段,prompt 等作者资产不经此端点外泄。
 *
 * @author OryxOS Contributors
 */
@RestController
@RequestMapping("/api/v1/profiles")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "核心阶段内网部署不做认证(§7.5 明确边界);API Key/JWT 是扩展阶段治理项。")
public class ProfileApiController {

  private final ProfileRegistry profileRegistry;

  /** 以 profile 索引创建只读 Controller. */
  public ProfileApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  /** 列出全部已注册 Profile 的摘要. */
  @GetMapping
  public ApiResponse<List<ProfileSummaryResponse>> list() {
    return ApiResponse.success(
        profileRegistry.all().stream().map(ProfileApiController::toSummary).toList());
  }

  private static ProfileSummaryResponse toSummary(Profile profile) {
    return new ProfileSummaryResponse(
        profile.name(),
        profile.description(),
        profile.identity() == null ? null : profile.identity().agentName(),
        profile.provider() == null ? null : profile.provider().name(),
        profile.provider() == null ? null : profile.provider().model());
  }

  /** Profile 摘要载荷. */
  public record ProfileSummaryResponse(
      String name, String description, String agentName, String provider, String model) {}
}
