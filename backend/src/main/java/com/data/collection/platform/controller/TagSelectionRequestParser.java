package com.data.collection.platform.controller;

import com.data.collection.platform.entity.TagSelectionRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class TagSelectionRequestParser {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<List<TagSelectionRequest>> TAG_SELECTIONS_TYPE =
      new TypeReference<>() {};

  private TagSelectionRequestParser() {}

  static List<TagSelectionRequest> parse(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return List.of();
    }
    try {
      List<TagSelectionRequest> parsed = OBJECT_MAPPER.readValue(rawValue, TAG_SELECTIONS_TYPE);
      return parsed == null ? List.of() : parsed;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to parse tagSelections json", exception);
    }
  }
}
