package com.oryxos.storage.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class UtcMillisInstantConverterTest {

  private final UtcMillisInstantConverter converter = new UtcMillisInstantConverter();

  @Test
  void usesCanonicalUtcMillisRegardlessOfDefaultZone() {
    TimeZone previous = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-08-31T00:00:00Z")))
          .isEqualTo("2026-08-31T00:00:00.000Z");
      assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-08-31T00:00:00.123987Z")))
          .isEqualTo("2026-08-31T00:00:00.123Z");
      assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-08-31T00:00:00Z")))
          .isLessThan(converter.convertToDatabaseColumn(Instant.parse("2026-08-31T00:00:00.001Z")));
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  @Test
  void requiresCanonicalRoundTripAndRejectsInvalidStorage() {
    assertThat(converter.convertToEntityAttribute("2026-08-31T00:00:00.123Z"))
        .isEqualTo(Instant.parse("2026-08-31T00:00:00.123Z"));
    for (String invalid :
        new String[] {
          null,
          "",
          "bad",
          "2026-08-31T00:00:00Z",
          "2026-08-31T00:00:00.1230Z",
          "2026-08-31T08:00:00.123+08:00"
        }) {
      assertThatThrownBy(() -> converter.convertToEntityAttribute(invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("记忆时间格式非法");
    }
    assertThatThrownBy(() -> converter.convertToDatabaseColumn(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
