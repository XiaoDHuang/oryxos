package com.oryxos.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 管理台托管回归:/admin、/admin/、子路由刷新都回落到 index.html;静态资产可读; /api/v1/** 不被 SPA 处理器吞掉(无映射时仍是 404,而不是
 * index.html).
 *
 * @author OryxOS Contributors
 */
@SpringBootTest(
    classes = AdminSpaWebConfigurationTest.SliceConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AdminSpaWebConfigurationTest {

  /** Web 模块无启动类,内嵌最小装配;数据源栈与 OryxOS 业务自动装配与本测试无关,排除掉. */
  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
      },
      // 三个 OryxOS 业务自动装配是包私有类,只能按名排除(见各模块 AutoConfiguration.imports)
      excludeName = {
        "com.oryxos.tool.ToolConfiguration",
        "com.oryxos.memory.MemoryConfiguration",
        "com.oryxos.memory.Mem0PropertiesConfiguration"
      })
  @Import(AdminSpaWebConfiguration.class)
  static class SliceConfig {}

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("admin根路径转发入口页;子路由刷新回落index.html内容")
  void adminPaths_fallbackToIndex() throws Exception {
    // MockMvc 不会像真实容器那样重派发 forward,根路径断言转发目标;真实重派发由 quickstart 人工项验收
    for (String path : new String[] {"/admin", "/admin/"}) {
      MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
      assertThat(result.getResponse().getForwardedUrl())
          .as("GET %s 应转发到入口页", path)
          .isEqualTo("/admin/index.html");
    }
    // 子路由走资源解析器回落,直接回 index.html 内容
    for (String path : new String[] {"/admin/sessions", "/admin/memory"}) {
      MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
      assertThat(result.getResponse().getContentAsString())
          .as("GET %s 的响应体", path)
          .contains("<div id=\"app\">");
    }
  }

  @Test
  @DisplayName("admin静态资产_原样可读")
  void adminAssets_servedDirectly() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/admin/logo.svg")).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getContentAsString()).contains("svg");
  }

  @Test
  @DisplayName("api路径不被SPA回落吞掉_无映射仍是404")
  void apiPath_notSwallowedBySpaFallback() throws Exception {
    mockMvc.perform(get("/api/v1/health")).andExpect(status().isNotFound());
  }
}
