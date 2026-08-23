package com.oryxos.web.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 所有 REST controller 共享的核心阶段 Spring MVC 约定.
 *
 * <p>核心阶段刻意对带版本的 API 放行所有来源。认证与来源白名单是扩展阶段的治理事项。
 *
 * @author OryxOS Contributors
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfiguration implements WebMvcConfigurer {

  private static final long CORS_MAX_AGE_SECONDS = 3600L;

  /** 为 API 端点配置核心阶段的开放 CORS 策略. */
  @Override
  @SuppressFBWarnings(
      value = "PERMISSIVE_CORS",
      justification = "核心阶段 API 刻意放行所有来源;认证与来源白名单是扩展阶段的治理特性。")
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/v1/**")
        .allowedOrigins("*")
        .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("X-Trace-Id")
        .allowCredentials(false)
        .maxAge(CORS_MAX_AGE_SECONDS);
  }
}
