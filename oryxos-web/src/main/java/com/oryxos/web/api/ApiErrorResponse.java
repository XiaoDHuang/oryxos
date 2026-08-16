package com.oryxos.web.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable error envelope required by the public API contract.
 *
 * @author OryxOS Contributors
 */
public record ApiErrorResponse(
    String errorCode,
    String message,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

  /** Validates the invariant fields of an error envelope. */
  public ApiErrorResponse {
    errorCode = requireText(errorCode, "errorCode");
    message = requireText(message, "message");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** Creates an error response using the error code's default public message. */
  public static ApiErrorResponse of(ErrorCode errorCode) {
    ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode");
    return of(requiredErrorCode, requiredErrorCode.getMessage());
  }

  /** Creates an error response with the current UTC timestamp. */
  public static ApiErrorResponse of(ErrorCode errorCode, String message) {
    ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode");
    return new ApiErrorResponse(requiredErrorCode.getCode(), message, Instant.now());
  }

  /** Returns the stable application error code using JavaBean naming. */
  public String getErrorCode() {
    return errorCode;
  }

  /** Returns the public error message using JavaBean naming. */
  public String getMessage() {
    return message;
  }

  /** Returns the error creation timestamp using JavaBean naming. */
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
