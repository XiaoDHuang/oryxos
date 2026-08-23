package com.oryxos.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global provider layer bound from {@code oryxos.providers} in application.yaml: which LLM
 * providers this instance connects to, and where each credential comes from. Model choice belongs
 * to the Profile layer, not here.
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
   * One provider declaration: unique name plus credential/endpoint.
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
