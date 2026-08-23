package com.oryxos.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 application.yaml 的 {@code oryxos.providers} 绑定的全局 provider 层:本实例连接哪些 LLM provider,以及各自凭据从何而来.
 * 模型选择属于 Profile 层,不在这里。
 *
 * @author OryxOS Contributors
 */
@ConfigurationProperties(prefix = "oryxos")
public class ProviderProperties {

  private List<ProviderEntry> providers = new ArrayList<>();

  public List<ProviderEntry> getProviders() {
    return new ArrayList<>(providers);
  }

  public void setProviders(List<ProviderEntry> providers) {
    this.providers = providers == null ? new ArrayList<>() : providers;
  }

  /**
   * 一条 provider 声明:唯一名称加凭据/端点.
   *
   * @author OryxOS Contributors
   */
  public static class ProviderEntry {

    private String name;

    private String apiKey;

    private String baseUrl;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }
  }
}
