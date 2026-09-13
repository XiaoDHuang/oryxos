package com.oryxos.core.profile;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 已加载 Profile 的内存索引,按名称键控. 启动扫描经构造器预填充;29 节起支持运行时 register/remove/exists —— 保序 Map 加同步方法
 * (保住既有有序输出契约,不引入并发框架新原语),为 Agent 目录扫描与 30 节生命周期铺路. register 过与启动加载同一套校验。
 *
 * @author OryxOS Contributors
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles = new LinkedHashMap<>();

  private final ProfileValidator validator;

  /** 创建以给定 profile 集合预填充的注册表(启动扫描结果). 未绑定 provider 名集合:历史只读装配点用,register 将拒绝服务。 */
  public ProfileRegistry(Collection<Profile> initialProfiles) {
    this(initialProfiles, null);
  }

  /** 创建预填充注册表并绑定全局 provider 名集合:register 过与 {@link ProfileLoader} 同一套校验. */
  public ProfileRegistry(Collection<Profile> initialProfiles, Set<String> globalProviderNames) {
    this.validator = globalProviderNames == null ? null : new ProfileValidator(globalProviderNames);
    if (initialProfiles != null) {
      for (Profile profile : initialProfiles) {
        profiles.put(profile.name(), profile);
      }
    }
  }

  /** 按名称查找 profile. */
  public synchronized Optional<Profile> find(String name) {
    return Optional.ofNullable(profiles.get(name));
  }

  /** 枚举全部已注册 profile(不可变视图,保插入序). 为 25 节 AgentScheduler.registerAll 的启动扫描而设. */
  public synchronized Collection<Profile> all() {
    return List.copyOf(profiles.values());
  }

  /** 运行时注册:先过与启动加载完全同一套校验,失败上抛同一异常同一消息(29 节 Agent 目录与 30 节生命周期共用入口). */
  public synchronized void register(Profile profile) {
    if (validator == null) {
      throw new IllegalStateException("该注册表未绑定 provider 名集合,运行时注册不可用");
    }
    validator.validate(profile);
    profiles.put(profile.name(), profile);
  }

  /** 按名移除;不存在为空操作. */
  public synchronized void remove(String name) {
    profiles.remove(name);
  }

  /** 按名判断是否已注册. */
  public synchronized boolean exists(String name) {
    return profiles.containsKey(name);
  }
}
