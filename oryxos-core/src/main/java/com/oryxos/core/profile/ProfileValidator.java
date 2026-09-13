package com.oryxos.core.profile;

import java.util.Set;

/**
 * 手写 YAML 与 Agent 目录两条来源共用的一套校验(课案"同一套校验"的单一实现):name 必填、provider.name 必须在全局 provider 名集合内. 启动加载路径
 * catch 后按既有格式记日志并跳过;运行时注册路径直接上抛 —— 同一异常类型、同一消息。 package-private:对外只暴露两条使用路径,不暴露校验器本身。
 *
 * @author OryxOS Contributors
 */
class ProfileValidator {

  private final Set<String> globalProviderNames;

  /** 创建按给定全局 provider 名集合校验的校验器. */
  ProfileValidator(Set<String> globalProviderNames) {
    this.globalProviderNames =
        globalProviderNames == null ? Set.of() : Set.copyOf(globalProviderNames);
  }

  /** 校验单个 profile;失败抛 IllegalArgumentException(固定消息,两条路径逐字一致). */
  void validate(Profile profile) {
    if (profile.name() == null || profile.name().isBlank()) {
      throw new IllegalArgumentException("缺少必填字段 'name'");
    }
    String providerName = profile.provider() == null ? null : profile.provider().name();
    if (providerName == null || !globalProviderNames.contains(providerName)) {
      throw new IllegalArgumentException("provider '" + providerName + "' 未在全局 provider 层声明");
    }
  }
}
