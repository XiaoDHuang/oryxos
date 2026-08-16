package com.oryxos.web.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.web.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class WebMvcConfigurationTest {

  @Test
  void permitsCoreApiPreflightFromAnyOrigin() throws Exception {
    try (AnnotationConfigWebApplicationContext context =
        new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context.register(TestConfiguration.class);
      context.refresh();
      MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

      mockMvc
          .perform(
              options("/api/v1/test")
                  .header("Origin", "https://example.test")
                  .header("Access-Control-Request-Method", "GET"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", "*"))
          .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import(WebMvcConfiguration.class)
  static class TestConfiguration {

    @Bean
    TestController testController() {
      return new TestController();
    }
  }

  @RestController
  static class TestController {

    @GetMapping("/api/v1/test")
    ApiResponse<String> get() {
      return ApiResponse.success("ok");
    }
  }
}
