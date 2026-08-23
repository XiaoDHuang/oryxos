package com.oryxos.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 所有核心 REST 端点共享的 OpenAPI 元数据.
 *
 * @author OryxOS Contributors
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

  @Bean
  OpenAPI oryxOsOpenApi(@Value("${info.app.version:1.0.0-SNAPSHOT}") String version) {
    return new OpenAPI()
        .info(new Info().title("OryxOS API").description("企业级 Agent OS 运行时 API").version(version));
  }
}
