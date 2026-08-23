package com.oryxos.provider;

/**
 * Thrown when a Profile references a provider name absent from the explicit registry. Failing loud
 * here is deliberate: silently falling back to a wrong model is worse than an error.
 *
 * @author OryxOS Contributors
 */
public class ProviderNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception naming the unregistered provider. */
  public ProviderNotFoundException(String providerName) {
    super("Provider not registered: " + providerName);
  }
}
