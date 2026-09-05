package com.oryxos.web.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 管理台 SPA 托管:静态资源出自 classpath:/static/admin/(前端构建产物,随仓库提交); 未命中的 /admin/** 路径一律回落 index.html,刷新子路由不
 * 404. 只挂 /admin/**, /api/v1/** 的解析不受影响.
 *
 * @author OryxOS Contributors
 */
@Configuration(proxyBeanMethods = false)
public class AdminSpaWebConfiguration implements WebMvcConfigurer {

  private static final String[] ADMIN_PATTERNS = {"/admin", "/admin/**"};

  private static final String ADMIN_LOCATION = "classpath:/static/admin/";

  private static final ClassPathResource INDEX = new ClassPathResource("/static/admin/index.html");

  /** 管理台根路径(/admin、/admin/)转发到入口页;资源处理器对空 lookup path 不会调回落解析器. */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/admin").setViewName("forward:/admin/index.html");
    registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
  }

  /** 注册带 SPA 回落的资源处理器. */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler(ADMIN_PATTERNS)
        .addResourceLocations(ADMIN_LOCATION)
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                // 根路径(/admin、/admin/ 的 within-mapping 为 "" 或 "/")直接给入口页,
                // 免得目录资源被当成可服务内容
                String normalized =
                    resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
                if (normalized.isEmpty()) {
                  return INDEX;
                }
                Resource requested = location.createRelative(normalized);
                if (requested.exists() && requested.isReadable()) {
                  return requested;
                }
                return INDEX;
              }
            });
  }
}
