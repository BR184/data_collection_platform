package com.data.collection.platform.service;

import com.data.collection.platform.entity.SourceTableSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class GitlabSourceMetadataSupport {
  private static final List<String> UPDATED_AT_CANDIDATES = List.of(
      "updatedat",
      "modifiedat",
      "lastmodifiedat",
      "lastupdatedat",
      "updatetime",
      "modifiedtime",
      "lastupdatetime",
      "lastmodifytime",
      "updatedon",
      "modifiedon",
      "lastmodifiedon",
      "lastupdatedon",
      "gmtmodified",
      "operatetime",
      "eventtime",
      "synctime");
  private static final List<String> CREATED_AT_CANDIDATES = List.of(
      "createdat",
      "createdon",
      "createtime",
      "inserttime",
      "writetime",
      "gmtcreate",
      "loadtime",
      "etltime");

  String resolveUpdatedAtColumn(List<String> columnNames) {
    return resolveCandidate(columnNames, UPDATED_AT_CANDIDATES)
        .orElseGet(() -> resolveCandidate(columnNames, CREATED_AT_CANDIDATES).orElse(null));
  }

  String resolveRowStrategy(String updatedAtColumn) {
    return updatedAtColumn == null || updatedAtColumn.isBlank() ? "FULL_ONLY" : "INCREMENTAL";
  }

  String buildSchemaFingerprint(SourceTableSchema schema) {
    String payload = schema.tableName()
        + "|pk=" + String.join(",", schema.primaryKeys())
        + "|updated=" + Objects.toString(schema.updatedAtColumn(), "")
        + "|columns=" + schema.columns().stream()
            .map(column -> column.columnName() + ":" + column.formattedType() + ":" + column.nullable())
            .reduce((left, right) -> left + "|" + right)
            .orElse("");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to hash source schema fingerprint", e);
    }
  }

  List<String> splitPrimaryKeys(String primaryKey) {
    if (primaryKey == null || primaryKey.isBlank()) {
      return List.of();
    }
    return List.of(primaryKey.split(","))
        .stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private java.util.Optional<String> resolveCandidate(List<String> columnNames, List<String> candidates) {
    return columnNames.stream()
        .map(columnName -> Map.entry(columnName, normalizeColumnName(columnName)))
        .filter(entry -> candidates.contains(entry.getValue()))
        .min(Comparator.comparingInt(entry -> candidates.indexOf(entry.getValue())))
        .map(Map.Entry::getKey);
  }

  private String normalizeColumnName(String columnName) {
    return columnName == null ? "" : columnName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
  }
}
