package com.oryxos.provider;

/**
 * 当 Profile 引用了显式注册表中不存在的 provider 名时抛出. 此处刻意响亮失败:静默 fallback 到错误模型比报错更糟。
 *
 * @author OryxOS Contributors
 */
public class ProviderNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 创建指明未注册 provider 的异常. */
  public ProviderNotFoundException(String providerName) {
    super("Provider 未注册: " + providerName);
  }
}
