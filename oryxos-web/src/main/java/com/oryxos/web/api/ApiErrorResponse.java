package com.oryxos.web.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;

/**
 * 公共 API 契约要求的不可变错误信封.
 *
 * @author OryxOS Contributors
 */
public record ApiErrorResponse(
    String errorCode,
    String message,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

  /** 校验错误信封的不变字段. */
  public ApiErrorResponse {
    errorCode = requireText(errorCode, "errorCode");
    message = requireText(message, "message");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** 用错误码的默认公开消息创建错误响应. */
  public static ApiErrorResponse of(ErrorCode errorCode) {
    ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode");
    return of(requiredErrorCode, requiredErrorCode.getMessage());
  }

  /** 创建带当前 UTC 时间戳的错误响应. */
  public static ApiErrorResponse of(ErrorCode errorCode, String message) {
    ErrorCode requiredErrorCode = Objects.requireNonNull(errorCode, "errorCode");
    return new ApiErrorResponse(requiredErrorCode.getCode(), message, Instant.now());
  }

  /** 以 JavaBean 命名返回稳定的应用错误码. */
  public String getErrorCode() {
    return errorCode;
  }

  /** 以 JavaBean 命名返回公开错误消息. */
  public String getMessage() {
    return message;
  }

  /** 以 JavaBean 命名返回错误创建时间戳. */
  public Instant getTimestamp() {
    return timestamp;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " 不能为空");
    }
    return value;
  }
}
