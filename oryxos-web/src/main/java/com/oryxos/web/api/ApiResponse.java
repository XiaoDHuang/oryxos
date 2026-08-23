package com.oryxos.web.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;

/**
 * OryxOS HTTP API 的不可变成功信封.
 *
 * <p>同时提供 record 风格访问器与 JavaBean 风格 getter,使契约对 Java 调用方、Jackson、 springdoc 以及模板/反射类集成保持一致。
 *
 * @author OryxOS Contributors
 */
public record ApiResponse<T>(
    String code,
    String message,
    T data,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {

  /** 成功的 API 调用返回的稳定 code. */
  public static final String SUCCESS_CODE = "SUCCESS";

  /** 成功的 API 调用返回的默认消息. */
  public static final String SUCCESS_MESSAGE = "OK";

  /** 校验响应信封的不变字段. */
  public ApiResponse {
    code = requireText(code, "code");
    message = requireText(message, "message");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
  }

  /** 创建带当前 UTC 时间戳的成功响应. */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, Instant.now());
  }

  /** 创建带调用方给定公开消息的成功响应. */
  public static <T> ApiResponse<T> success(String message, T data) {
    return new ApiResponse<>(SUCCESS_CODE, message, data, Instant.now());
  }

  /** 以 JavaBean 命名返回稳定响应码. */
  public String getCode() {
    return code;
  }

  /** 以 JavaBean 命名返回公开响应消息. */
  public String getMessage() {
    return message;
  }

  /** 以 JavaBean 命名返回响应载荷. */
  public T getData() {
    return data;
  }

  /** 以 JavaBean 命名返回响应创建时间戳. */
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
