package com.oryxos.web.api;

import com.oryxos.provider.ProviderNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.common.OpenAiApiClientErrorException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把 controller 故障映射为稳定的 OryxOS JSON 错误契约.
 *
 * @author OryxOS Contributors
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 处理畸形或非法的请求输入. */
  @ExceptionHandler({
    BindException.class,
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    ServletRequestBindingException.class
  })
  public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
    LOGGER.debug("非法 HTTP 请求", exception);
    return response(ErrorCode.INVALID_REQUEST);
  }

  /** 处理对不存在资源的请求. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException exception) {
    LOGGER.debug("HTTP 资源不存在", exception);
    return response(ErrorCode.RESOURCE_NOT_FOUND);
  }

  /** 处理目标资源不支持的 HTTP 方法. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception) {
    LOGGER.debug("HTTP 方法不被支持", exception);
    return response(ErrorCode.METHOD_NOT_ALLOWED);
  }

  /** 处理媒体类型不支持的请求体. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception) {
    LOGGER.debug("HTTP 媒体类型不被支持", exception);
    return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  /** 处理带显式公开错误码的故障. */
  @ExceptionHandler(OryxException.class)
  public ResponseEntity<ApiErrorResponse> handleOryxException(OryxException exception) {
    LOGGER.warn("OryxOS 请求以已处理的应用错误失败");
    return response(exception.getErrorCode(), exception.getMessage());
  }

  /** 处理请求参数层的非法值(端口校验拒绝等),消息来自调用方输入、可外发. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
      IllegalArgumentException exception) {
    LOGGER.debug("请求参数非法", exception);
    return response(ErrorCode.INVALID_REQUEST, exception.getMessage());
  }

  /** 处理 Profile 引用未注册 provider 的故障. */
  @ExceptionHandler(ProviderNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleProviderNotFound(
      ProviderNotFoundException exception) {
    LOGGER.warn("Profile 引用了未注册的 provider");
    return response(ErrorCode.PROVIDER_UNAVAILABLE, exception.getMessage());
  }

  /**
   * 处理 LLM 调用的真实失败载体:网络层为 RestClientException 族,API 错误响应为 Spring AI 1.1.8 核实的
   * OpenAiApiClientErrorException. 对外只给固定话术——provider 侧错误体可能带回请求细节,不透出。
   */
  @ExceptionHandler({RestClientException.class, OpenAiApiClientErrorException.class})
  public ResponseEntity<ApiErrorResponse> handleProviderCallFailure(RuntimeException exception) {
    LOGGER.warn("LLM provider 调用失败", exception);
    return response(ErrorCode.PROVIDER_UNAVAILABLE);
  }

  /** 处理 Agent 调用超过 60 秒上限的异步超时. */
  @ExceptionHandler(AsyncRequestTimeoutException.class)
  public ResponseEntity<ApiErrorResponse> handleAgentTimeout(
      AsyncRequestTimeoutException exception) {
    LOGGER.warn("Agent 调用超时");
    return response(ErrorCode.AGENT_TIMEOUT);
  }

  /** 处理意料之外的故障,不暴露内部细节. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
    LOGGER.error("HTTP 请求发生意外故障", exception);
    return response(ErrorCode.INTERNAL_ERROR);
  }

  private static ResponseEntity<ApiErrorResponse> response(ErrorCode errorCode) {
    return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiErrorResponse.of(errorCode));
  }

  private static ResponseEntity<ApiErrorResponse> response(ErrorCode errorCode, String message) {
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ApiErrorResponse.of(errorCode, message));
  }
}
