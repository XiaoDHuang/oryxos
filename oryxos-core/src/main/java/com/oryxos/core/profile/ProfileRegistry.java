package com.oryxos.core.profile;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已加载 Profile 的内存索引,按名称键控. 本节只有启动扫描注册路径;运行时 {@code register()} 方法随生命周期课到来。
 *
 * @author OryxOS Contributors
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles = new LinkedHashMap<>();

  /** 创建以给定 profile 集合预填充的注册表(启动扫描结果). */
  public ProfileRegistry(Collection<Profile> initialProfiles) {
    if (initialProfiles != null) {
      for (Profile profile : initialProfiles) {
        profiles.put(profile.name(), profile);
      }
    }
  }

  /** 按名称查找 profile. */
  public Optional<Profile> find(String name) {
    return Optional.ofNullable(profiles.get(name));
  }
}
