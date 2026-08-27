package com.oryxos.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolArgumentValidatorTest {

  private static final String SCHEMA =
      """
      {"type":"object","properties":{"path":{"type":"string"}},
       "required":["path"],"additionalProperties":false}
      """;

  private final ToolArgumentValidator validator = new ToolArgumentValidator();

  @Test
  @DisplayName("合法对象Schema与参数可以通过")
  void acceptsValidObjectArguments() {
    assertThatCode(() -> validator.validateSchema(SCHEMA)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateArguments(SCHEMA, "{\"path\":\"文件.txt\"}"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("缺字段类型错误和额外字段均在调用前拒绝")
  void rejectsMissingWrongAndExtraFields() {
    assertThatThrownBy(() -> validator.validateArguments(SCHEMA, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validateArguments(SCHEMA, "{\"path\":7}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validateArguments(SCHEMA, "{\"path\":\"x\",\"extra\":true}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("参数必须是单一JSON对象且不允许重复键")
  void rejectsMalformedAndNonObjectArguments() {
    for (String input :
        new String[] {"", "[]", "null", "broken", "{} {}", "{\"path\":1,\"path\":2}"}) {
      assertThatThrownBy(() -> validator.validateArguments(SCHEMA, input))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("空白非对象和未知方言Schema不能注册")
  void rejectsInvalidSchemasAndDialects() {
    for (String schema :
        new String[] {
          "",
          "[]",
          "broken",
          "{\"type\":\"array\"}",
          "{\"$schema\":\"https://unknown.invalid/schema\",\"type\":\"object\"}"
        }) {
      assertThatThrownBy(() -> validator.validateSchema(schema))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("本地引用保留并参与参数校验")
  void validatesLocalReferences() {
    String schema =
        """
        {"type":"object","$defs":{"name":{"type":"string","minLength":2}},
         "properties":{"name":{"$ref":"#/$defs/name"}},"required":["name"]}
        """;
    assertThatCode(() -> validator.validateArguments(schema, "{\"name\":\"名字\"}"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateArguments(schema, "{\"name\":\"\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("组合约束与对象形式额外属性不会丢失")
  void preservesCompositionAndAdditionalPropertySchemas() {
    String schema =
        """
        {"type":"object","allOf":[{"properties":{"amount":{"minimum":3}}}],
         "additionalProperties":{"type":"integer"}}
        """;
    assertThatCode(() -> validator.validateArguments(schema, "{\"amount\":3}"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateArguments(schema, "{\"amount\":2}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validateArguments(schema, "{\"other\":\"text\"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("高精度数字不能在首次解析时被舍入")
  void preservesDecimalPrecision() {
    String schema =
        """
        {"type":"object","properties":{"amount":{"minimum":0.10000000000000001}}}
        """;
    assertThatCode(() -> validator.validateArguments(schema, "{\"amount\":0.10000000000000001}"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateArguments(schema, "{\"amount\":0.1}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("数据属性名ref不是Schema引用关键字")
  void allowsReferenceNamedDataProperty() {
    String schema = "{\"type\":\"object\",\"properties\":{\"$ref\":{\"type\":\"string\"}}}";
    assertThatCode(() -> validator.validateArguments(schema, "{\"$ref\":\"literal\"}"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("外部引用被拒绝且不会发起HTTP请求")
  void rejectsExternalReferencesWithoutNetwork() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setBody("{\"type\":\"object\"}"));
      String schema =
          "{\"type\":\"object\",\"allOf\":[{\"$ref\":\"" + server.url("/schema") + "\"}]}";
      assertThatThrownBy(() -> validator.validateSchema(schema))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(server.getRequestCount()).isZero();
    }
  }

  @Test
  @DisplayName("校验错误不回显敏感参数或原始JSON")
  void doesNotEchoSensitiveInput() {
    assertThatThrownBy(
            () -> validator.validateArguments(SCHEMA, "{\"path\":{\"token\":\"sensitive-value\"}}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("sensitive-value");
  }
}
