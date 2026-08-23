package com.oryxos.web.api;

import java.io.Serial;
import java.util.Objects;

/**
 * 带显式公开 API 错误码的故障的基异常.
 *
 * @author OryxOS Contributors
 */
public final class OryxException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;

  /** 创建带公开错误码与消息的应用异常. */
  public OryxException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** 创建保留内部 cause 的应用异常. */
  public OryxException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  /** 返回 web 错误处理器暴露的稳定错误码. */
  public ErrorCode errorCode() {
    return errorCode;
  }

  /** 以 JavaBean 命名返回稳定错误码. */
  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
