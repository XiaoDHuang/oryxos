package com.oryxos.web.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable success envelope for the OryxOS HTTP API.
 *
 * <p>Record-style accessors and JavaBean-style getters are both provided so the contract works
 * consistently with Java callers, Jackson, springdoc, and template/reflection-based integrations.
 *
 * @author OryxOS Contributors
 */
public record ApiResponse<T>(
    String code,
    String message,
    T data,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

  /** Stable code returned by successful API calls. */
  public static final String SUCCESS_CODE = "SUCCESS";

  /** Default message returned by successful API calls. */
  public static final String SUCCESS_MESSAGE = "OK";

  /** Validates the invariant fields of a response envelope. */
  public ApiResponse {
    code = requireText(code, "code");
    message = requireText(message, "message");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Creates a successful response with the current UTC timestamp. */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, Instant.now());
  }

  /** Creates a successful response with a caller-supplied public message. */
  public static <T> ApiResponse<T> success(String message, T data) {
    return new ApiResponse<>(SUCCESS_CODE, message, data, Instant.now());
  }

  /** Returns the stable response code using JavaBean naming. */
  public String getCode() {
    return code;
  }

  /** Returns the public response message using JavaBean naming. */
  public String getMessage() {
    return message;
  }

  /** Returns the response payload using JavaBean naming. */
  public T getData() {
    return data;
  }

  /** Returns the response creation timestamp using JavaBean naming. */
  public Instant getTimestamp() {
    return timestamp;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
