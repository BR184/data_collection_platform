package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.SourceTableSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PrimaryKeySignatureSupport {
  private static final String DELIMITER = "\u001F";

  private PrimaryKeySignatureSupport() {
  }

  public static List<String> primaryKeyColumns(SourceTableSchema schema) {
    if (schema == null || schema.primaryKeys() == null || schema.primaryKeys().isEmpty()) {
      return List.of("id");
    }
    List<String> keys = schema.primaryKeys().stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
    return keys.isEmpty() ? List.of("id") : keys;
  }

  public static String signature(List<String> primaryKeys, Map<String, Object> row) {
    return primaryKeys.stream()
        .map(primaryKey -> Objects.toString(row.get(primaryKey), ""))
        .reduce((left, right) -> left + DELIMITER + right)
        .orElse("");
  }

  public static String encodeCursor(JsonUtils jsonUtils, List<String> primaryKeys, Map<String, Object> row) {
    List<String> values = primaryKeys.stream()
        .map(primaryKey -> Objects.toString(row.get(primaryKey), ""))
        .toList();
    return jsonUtils.toJson(values);
  }

  public static List<String> decodeCursor(JsonUtils jsonUtils, String cursor) {
    List<String> values = jsonUtils.fromJson(cursor, new TypeReference<>() {});
    return values == null ? List.of() : values;
  }
}
