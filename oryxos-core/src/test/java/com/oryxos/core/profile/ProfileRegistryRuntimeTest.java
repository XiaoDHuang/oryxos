package com.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运行时注册(harness):register 后立即可见;非法配置与启动加载路径同一异常类型、同一消息. */
class ProfileRegistryRuntimeTest {

  private static final Set<String> PROVIDERS = Set.of("deepseek");

  private final ProfileValidator validator = new ProfileValidator(PROVIDERS);

  private final ProfileRegistry registry = new ProfileRegistry(List.of(), PROVIDERS);

  private static Profile validProfile(String name) {
    return new Profile(
        name,
        "测试",
        new Profile.Identity("小欧", "人格", null),
        new Profile.Provider("deepseek", "deepseek-chat", 0.2, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        null);
  }

  @Test
  @DisplayName("register后立即find可见且exists为真")
  void registerThenImmediatelyVisible() {
    registry.register(validProfile("agent-a"));

    assertThat(registry.exists("agent-a")).isTrue();
    assertThat(registry.find("agent-a")).isPresent();
    assertThat(registry.all()).extracting(Profile::name).contains("agent-a");
  }

  @Test
  @DisplayName("remove后不可见;移除不存在项为空操作")
  void removeThenInvisibleAndNoopOnMissing() {
    registry.register(validProfile("agent-a"));
    registry.remove("agent-a");

    assertThat(registry.exists("agent-a")).isFalse();
    assertThat(registry.find("agent-a")).isEmpty();

    registry.remove("never-existed");
    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("provider未声明_运行时与启动路径同一异常同一消息")
  void invalidProvider_sameExceptionAndMessageAsStartupPath() {
    Profile bad =
        new Profile(
            "agent-bad",
            "测试",
            new Profile.Identity("小欧", "人格", null),
            new Profile.Provider("ghost", "m", 0.2, null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            null);

    // 启动加载路径:ProfileLoader 调同一 ProfileValidator,catch 后按既有格式记日志并跳过
    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider 'ghost' 未在全局 provider 层声明");
    // 运行时注册路径:register 直接上抛,与启动路径逐字一致
    assertThatThrownBy(() -> registry.register(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("provider 'ghost' 未在全局 provider 层声明");
    assertThat(registry.exists("agent-bad")).isFalse();
  }

  @Test
  @DisplayName("缺name_运行时与启动路径同一异常同一消息")
  void missingName_sameExceptionAndMessageAsStartupPath() {
    Profile bad = validProfile(" ");
    assertThatThrownBy(() -> validator.validate(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("缺少必填字段 'name'");
    assertThatThrownBy(() -> registry.register(bad))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("缺少必填字段 'name'");
  }

  @Test
  @DisplayName("既有构造器与find/all行为不回退")
  void legacyConstructorKeepsReadOnlyBehavior() {
    ProfileRegistry legacy = new ProfileRegistry(List.of(validProfile("seed")));
    assertThat(legacy.find("seed")).isPresent();
    assertThat(legacy.all()).hasSize(1);
  }
}
