package com.oryxos.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsProviderFailureToServiceUnavailable() {
    OryxException exception =
        new OryxException(ErrorCode.PROVIDER_UNAVAILABLE, "Provider unavailable");

    ResponseEntity<ApiErrorResponse> response = handler.handleOryxException(exception);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
        .containsExactly("PROVIDER_UNAVAILABLE", "Provider unavailable");
  }

  @Test
  void mapsUnsupportedMethodToMethodNotAllowed() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PATCH"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrorCode()).isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void mapsUnsupportedContentTypeToUnsupportedMediaType() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnsupportedMediaType(
            new HttpMediaTypeNotSupportedException("application/xml"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  void hidesUnexpectedExceptionDetails() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleUnexpectedException(new IllegalStateException("sensitive detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
        .containsExactly("INTERNAL_ERROR", "服务器内部错误");
  }
}
