package com.data.collection.platform.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformJacksonConfiguration {
  public static final ZoneId API_TIME_ZONE = ZoneId.of("Asia/Shanghai");

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer platformDateTimeJacksonCustomizer() {
    return builder -> builder
        .timeZone(TimeZone.getTimeZone(API_TIME_ZONE))
        .serializerByType(LocalDateTime.class, localDateTimeSerializer())
        .deserializerByType(LocalDateTime.class, localDateTimeDeserializer());
  }

  public static JsonSerializer<LocalDateTime> localDateTimeSerializer() {
    return new JsonSerializer<>() {
      @Override
      public void serialize(
          LocalDateTime value,
          JsonGenerator jsonGenerator,
          SerializerProvider serializerProvider) throws IOException {
        if (value == null) {
          jsonGenerator.writeNull();
          return;
        }
        jsonGenerator.writeString(value
            .atZone(API_TIME_ZONE)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
      }
    };
  }

  public static JsonDeserializer<LocalDateTime> localDateTimeDeserializer() {
    return new JsonDeserializer<>() {
      @Override
      public LocalDateTime deserialize(
          JsonParser jsonParser,
          DeserializationContext context) throws IOException {
        if (jsonParser.currentToken() == JsonToken.VALUE_NULL) {
          return null;
        }
        String value = jsonParser.getValueAsString();
        if (value == null || value.isBlank()) {
          return null;
        }
        String text = value.trim();
        try {
          return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
              .atZoneSameInstant(API_TIME_ZONE)
              .toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
          return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
      }
    };
  }
}
