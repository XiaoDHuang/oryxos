package com.oryxos.web.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Core-stage Spring MVC conventions shared by all REST controllers.
 *
 * <p>The core stage intentionally permits every origin for the versioned API. Authentication and an
 * origin allowlist are extension-stage governance concerns.
 *
 * @author OryxOS Contributors
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfiguration implements WebMvcConfigurer {

  private static final long CORS_MAX_AGE_SECONDS = 3600L;

  /** Configures the core-stage open CORS policy for API endpoints. */
  @Override
  @SuppressFBWarnings(
      value = "PERMISSIVE_CORS",
      justification =
          "The core-stage API intentionally allows all origins; authentication and origin"
              + " allowlisting are extension-stage governance features.")
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
