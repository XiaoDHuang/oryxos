package com.oryxos.core.react;

import com.oryxos.core.profile.Profile;
import org.springframework.lang.Nullable;

/**
 * 当前 agent 的 Profile 的请求级载体. 工具签名刻意不接收 Profile(改动工具接口代价太大), 因此 AgentService 在入口把它植入这里,并在 finally
 * 块中清除;虚拟线程下每个请求独占 载体线程,profile 之间绝不会串话。
 *
 * @author OryxOS Contributors
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  /** 为本请求线程植入当前 profile. */
  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 返回当前 profile;在 AgentService 处理的请求之外返回 null. */
  @Nullable
  public static Profile current() {
    return CURRENT.get();
  }

  /** 清除当前 profile;AgentService 必须在每条退出路径的 finally 中调用它. */
  public static void clear() {
    CURRENT.remove();
  }
}
