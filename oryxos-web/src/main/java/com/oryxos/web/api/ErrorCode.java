package com.oryxos.web.api;

import org.springframework.http.HttpStatus;

/**
 * 稳定的应用错误码及其 HTTP 状态映射.
 *
 * @author OryxOS Contributors
 */
public enum ErrorCode {
  /** 请求畸形或未通过校验. */
  INVALID_REQUEST("INVALID_REQUEST", "请求无效", HttpStatus.BAD_REQUEST),

  /** 请求的资源不存在. */
  RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),

  /** 资源不支持请求的 HTTP 方法. */
  METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不被支持", HttpStatus.METHOD_NOT_ALLOWED),

  /** 请求体使用了不支持的媒体类型. */
  UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", "不支持的媒体类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

  /** 配置的语言模型 provider 不可用. */
  PROVIDER_UNAVAILABLE("PROVIDER_UNAVAILABLE", "Provider 不可用", HttpStatus.SERVICE_UNAVAILABLE),

  /** Agent 调用超过核心阶段 60 秒时限. */
  AGENT_TIMEOUT("AGENT_TIMEOUT", "Agent 调用超时", HttpStatus.GATEWAY_TIMEOUT),

  /** 发生了意料之外的内部故障. */
  INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(String code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }

  /** 返回暴露给 API 客户端的稳定错误码. */
  public String code() {
    return code;
  }

  /** 以 JavaBean 命名返回稳定错误码. */
  public String getCode() {
    return code;
  }

  /** 返回默认的公开错误消息. */
  public String message() {
    return message;
  }

  /** 以 JavaBean 命名返回默认的公开错误消息. */
  public String getMessage() {
    return message;
  }

  /** 返回该错误关联的 HTTP 状态. */
  public HttpStatus httpStatus() {
    return httpStatus;
  }

  /** 以 JavaBean 命名返回 HTTP 状态. */
  public HttpStatus getHttpStatus() {
    return httpStatus;
  }
}
