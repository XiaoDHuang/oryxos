package com.oryxos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  @Test
  void exposesRecordAndJavaBeanAccessors() {
    ApiResponse<String> response = ApiResponse.success("Created", "session-1");

    assertThat(response.getCode()).isEqualTo(response.code()).isEqualTo("SUCCESS");
    assertThat(response.getMessage()).isEqualTo(response.message()).isEqualTo("Created");
    assertThat(response.getData()).isEqualTo(response.data()).isEqualTo("session-1");
    assertThat(response.getTimestamp()).isEqualTo(response.timestamp()).isNotNull();
  }

  @Test
  void serializesStableSuccessContractWithoutDuplicateProperties() throws Exception {
    JsonNode json = objectMapper.valueToTree(ApiResponse.success("session-1"));

    assertThat(json.size()).isEqualTo(4);
    assertThat(json.get("code").asText()).isEqualTo("SUCCESS");
    assertThat(json.get("message").asText()).isEqualTo("OK");
    assertThat(json.get("data").asText()).isEqualTo("session-1");
    assertThat(json.get("timestamp").isTextual()).isTrue();
  }

  @Test
  void serializesStableErrorContractAndDefaultMessage() {
    ApiErrorResponse response = ApiErrorResponse.of(ErrorCode.AGENT_TIMEOUT);
    JsonNode json = objectMapper.valueToTree(response);

    assertThat(response.getErrorCode()).isEqualTo(response.errorCode()).isEqualTo("AGENT_TIMEOUT");
    assertThat(response.getMessage()).isEqualTo(response.message()).isEqualTo("Agent 调用超时");
    assertThat(response.getTimestamp()).isEqualTo(response.timestamp()).isNotNull();
    assertThat(json.size()).isEqualTo(3);
    assertThat(json.get("errorCode").asText()).isEqualTo("AGENT_TIMEOUT");
    assertThat(json.get("timestamp").isTextual()).isTrue();
  }

  @Test
  void rejectsBlankInvariantFields() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ApiResponse<>(" ", "OK", null, Instant.now()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ApiErrorResponse("INTERNAL_ERROR", "", Instant.now()));
  }
}
