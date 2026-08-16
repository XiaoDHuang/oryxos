package com.oryxos.web.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps controller failures to the stable OryxOS JSON error contract.
 *
 * @author OryxOS Contributors
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Handles malformed or invalid request input. */
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
    LOGGER.debug("Invalid HTTP request", exception);
    return response(ErrorCode.INVALID_REQUEST);
  }

  /** Handles requests for resources that do not exist. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException exception) {
    LOGGER.debug("HTTP resource not found", exception);
    return response(ErrorCode.RESOURCE_NOT_FOUND);
  }

  /** Handles HTTP methods that are not supported by the target resource. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception) {
    LOGGER.debug("HTTP method not allowed", exception);
    return response(ErrorCode.METHOD_NOT_ALLOWED);
  }

  /** Handles request bodies with an unsupported media type. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception) {
    LOGGER.debug("HTTP media type not supported", exception);
    return response(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  /** Handles failures with an explicit public error code. */
  @ExceptionHandler(OryxException.class)
  public ResponseEntity<ApiErrorResponse> handleOryxException(OryxException exception) {
    LOGGER.warn("OryxOS request failed with a handled application error");
    return response(exception.getErrorCode(), exception.getMessage());
  }

  /** Handles unexpected failures without exposing internal details. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
    LOGGER.error("Unexpected HTTP request failure", exception);
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
