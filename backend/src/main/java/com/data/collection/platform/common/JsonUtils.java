package com.data.collection.platform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JsonUtils {
  private final ObjectMapper objectMapper;

  public JsonUtils(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize json", e);
    }
  }

  public List<String> toStringList(String json) {
    if (json == null || json.isBlank()) {
      return Collections.emptyList();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to parse string list json", e);
    }
  }

  public Map<String, Object> toMap(String json) {
    if (json == null || json.isBlank()) {
      return Collections.emptyMap();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to parse map json", e);
    }
  }

  public <T> T fromJson(String json, TypeReference<T> typeReference) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, typeReference);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to parse json", e);
    }
  }

  public <T> T fromJson(String json, Class<T> type) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to parse json", e);
    }
  }
}
