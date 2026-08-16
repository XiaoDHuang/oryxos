package com.oryxos.web.api;

import org.springframework.http.HttpStatus;

/**
 * Stable application error codes and their HTTP status mapping.
 *
 * @author OryxOS Contributors
 */
public enum ErrorCode {
  /** The request is malformed or fails validation. */
  INVALID_REQUEST("INVALID_REQUEST", "Invalid request", HttpStatus.BAD_REQUEST),

  /** The requested resource does not exist. */
  RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "Resource not found", HttpStatus.NOT_FOUND),

  /** The requested HTTP method is not supported by the resource. */
  METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "Method not allowed", HttpStatus.METHOD_NOT_ALLOWED),

  /** The request body uses an unsupported media type. */
  UNSUPPORTED_MEDIA_TYPE(
      "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

  /** The configured language model provider is unavailable. */
  PROVIDER_UNAVAILABLE(
      "PROVIDER_UNAVAILABLE", "Provider unavailable", HttpStatus.SERVICE_UNAVAILABLE),

  /** The agent invocation exceeded the core-stage 60-second deadline. */
  AGENT_TIMEOUT("AGENT_TIMEOUT", "Agent invocation timed out", HttpStatus.GATEWAY_TIMEOUT),

  /** An unexpected internal failure occurred. */
  INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(String code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }

  /** Returns the stable code exposed to API clients. */
  public String code() {
    return code;
  }

  /** Returns the stable code using JavaBean naming. */
  public String getCode() {
    return code;
  }

  /** Returns the default public error message. */
  public String message() {
    return message;
  }

  /** Returns the default public error message using JavaBean naming. */
  public String getMessage() {
    return message;
  }

  /** Returns the HTTP status associated with this error. */
  public HttpStatus httpStatus() {
    return httpStatus;
  }

  /** Returns the HTTP status using JavaBean naming. */
  public HttpStatus getHttpStatus() {
    return httpStatus;
  }
}
