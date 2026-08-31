package com.oryxos.storage.memory;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 记忆时间采用独立映射,不改变既有会话和审计表.
 *
 * @author OryxOS Contributors
 */
@Converter(autoApply = false)
public class UtcMillisInstantConverter implements AttributeConverter<Instant, String> {

  private static final DateTimeFormatter FORMAT =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

  @Override
  public String convertToDatabaseColumn(Instant value) {
    if (value == null) {
      throw new IllegalArgumentException("记忆时间不能为空");
    }
    return FORMAT.format(value.truncatedTo(ChronoUnit.MILLIS));
  }

  @Override
  public Instant convertToEntityAttribute(String value) {
    if (value == null) {
      throw new IllegalArgumentException("记忆时间格式非法");
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!convertToDatabaseColumn(parsed).equals(value)) {
        throw new IllegalArgumentException("记忆时间格式非法");
      }
      return parsed;
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("记忆时间格式非法");
    }
  }
}
