package com.oryxos.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.oryxos.provider.ProviderNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.common.OpenAiApiClientErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

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

  @Test
  void mapsIllegalArgumentToBadRequest() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleIllegalArgument(new IllegalArgumentException("消息为空或超过 32KB"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
        .containsExactly("INVALID_REQUEST", "消息为空或超过 32KB");
  }

  @Test
  void mapsProviderNotFoundToServiceUnavailable() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleProviderNotFound(new ProviderNotFoundException("deepseek"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
        .containsExactly("PROVIDER_UNAVAILABLE", "Provider 未注册: deepseek");
  }

  @Test
  void mapsProviderCallFailureToServiceUnavailableWithoutDetails() {
    ResponseEntity<ApiErrorResponse> network =
        handler.handleProviderCallFailure(new ResourceAccessException("Connection refused"));
    ResponseEntity<ApiErrorResponse> apiError =
        handler.handleProviderCallFailure(new OpenAiApiClientErrorException("401 invalid key"));

    for (ResponseEntity<ApiErrorResponse> response : new ResponseEntity[] {network, apiError}) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(response.getBody())
          .isNotNull()
          .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
          .containsExactly("PROVIDER_UNAVAILABLE", "Provider 不可用");
    }
  }

  @Test
  void mapsAsyncTimeoutToGatewayTimeout() {
    ResponseEntity<ApiErrorResponse> response =
        handler.handleAgentTimeout(new AsyncRequestTimeoutException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    assertThat(response.getBody())
        .isNotNull()
        .extracting(ApiErrorResponse::getErrorCode, ApiErrorResponse::getMessage)
        .containsExactly("AGENT_TIMEOUT", "Agent 调用超时");
  }
}
