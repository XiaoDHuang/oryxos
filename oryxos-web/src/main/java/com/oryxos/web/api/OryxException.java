package com.oryxos.web.api;

import java.io.Serial;
import java.util.Objects;

/**
 * Base exception for failures that have an explicit public API error code.
 *
 * @author OryxOS Contributors
 */
public final class OryxException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;

  /** Creates an application exception with a public error code and message. */
  public OryxException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** Creates an application exception while retaining its internal cause. */
  public OryxException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** Returns the stable error code exposed by the web error handler. */
  public ErrorCode errorCode() {
    return errorCode;
  }

  /** Returns the stable error code using JavaBean naming. */
  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
