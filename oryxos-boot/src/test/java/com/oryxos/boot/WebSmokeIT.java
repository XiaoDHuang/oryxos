package com.oryxos.boot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.OryxOsApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 冒烟:起真实应用上下文(完整组件扫描 + JPA repository 扫描 + MVC 全栈),不碰模型/网络/ 外部进程,因此不打 integration 标签——每次 mvn
 * test 都守 Bean 装配(18 节 "Found 0 repositories" 类回归在这里第一时间红). 无 provider key 时 provider 被跳过、profile
 * 跳过,上下文照常起来, 冒烟只断言可达性与信封形状,不断言业务内容.
 *
 * @author OryxOS Contributors
 */
@SpringBootTest(
    classes = OryxOsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class WebSmokeIT {

  static {
    // 静态初始化先于 Spring 上下文创建:在模块 basedir 下建最小工作区夹具(.gitignore 已覆盖 .oryxos/)。
    // CoreEngineConfiguration.requireWorkspace 与 SQLite 数据源都按相对路径 .oryxos 解析。
    try {
      Files.createDirectories(Path.of(".oryxos/profiles"));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("health真实链路可达_返回ok信封")
  void health_reachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  @DisplayName("info真实链路可达_带应用名与providers数组")
  void info_reachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.name").value("OryxOS"))
        .andExpect(jsonPath("$.data.providers").isArray());
  }

  @Test
  @DisplayName("profiles与tools真实链路可达_数组信封")
  void profilesAndTools_reachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
    // 内置工具组在真实装配下必须非空(File/Shell/Http/Notify/Memory)
    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isNotEmpty());
  }

  @Test
  @DisplayName("OpenAPI文档真实链路可达")
  void apiDocs_reachable() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("管理台在真实装配下被托管_根路径转发入口页,子路由回落内容")
  void adminSpa_hosted() throws Exception {
    // MockMvc 不重派发 forward:根路径只断言转发目标,真实容器行为走 quickstart 人工项
    MvcResult root = mockMvc.perform(get("/admin/")).andExpect(status().isOk()).andReturn();
    org.assertj.core.api.Assertions.assertThat(root.getResponse().getForwardedUrl())
        .isEqualTo("/admin/index.html");
    // 子路由经资源解析器回落,直接回入口页内容
    mockMvc
        .perform(get("/admin/sessions"))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .contains("<div id=\"app\">"));
  }
}
