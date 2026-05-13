package com.data.collection.platform.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;
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
        .serializerByType(LocalDateTime.class, localDateTimeSerializer());
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
}
