package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SyncRunPayload(
    SyncType syncType,
    SyncTriggerType triggerType,
    String reason,
    List<String> sourceTables,
    String primaryTableName,
    Long parentRunId,
    Boolean fullBuild,
    Boolean manualFullRebuild,
    List<PreciseTarget> preciseTargets) {
  private static final TypeReference<SyncRunPayload> TYPE_REFERENCE = new TypeReference<>() {};

  public SyncRunPayload {
    sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
    preciseTargets = preciseTargets == null ? List.of() : List.copyOf(preciseTargets);
  }

  public static TypeReference<SyncRunPayload> typeReference() {
    return TYPE_REFERENCE;
  }

  public static SyncRunPayload empty() {
    return new SyncRunPayload(null, null, null, List.of(), null, null, null, null, List.of());
  }

  public static SyncRunPayload create(
      SyncType syncType,
      SyncTriggerType triggerType,
      String reason,
      List<String> sourceTables,
      String primaryTableName,
      Long parentRunId,
      Boolean fullBuild) {
    return new SyncRunPayload(
        syncType,
        triggerType,
        reason,
        sourceTables,
        primaryTableName,
        parentRunId,
        fullBuild,
        null,
        List.of());
  }

  public Map<String, Object> toMap(Map<String, Object> extraPayload) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (syncType != null) {
      payload.put("syncType", syncType.name());
    }
    if (triggerType != null) {
      payload.put("triggerType", triggerType.name());
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    payload.put("sourceTables", sourceTables);
    if (primaryTableName != null) {
      payload.put("primaryTableName", primaryTableName);
    }
    if (parentRunId != null) {
      payload.put("parentRunId", parentRunId);
    }
    if (fullBuild != null) {
      payload.put("fullBuild", fullBuild);
    }
    if (manualFullRebuild != null) {
      payload.put("manualFullRebuild", manualFullRebuild);
    }
    if (!preciseTargets.isEmpty()) {
      payload.put("preciseTargets", preciseTargets);
    }
    if (extraPayload != null && !extraPayload.isEmpty()) {
      payload.putAll(extraPayload);
    }
    return payload;
  }

  public boolean fullBuildEnabled() {
    return Boolean.TRUE.equals(fullBuild);
  }

  public boolean manualFullRebuildEnabled() {
    return Boolean.TRUE.equals(manualFullRebuild);
  }

  public List<String> normalizedSourceTables() {
    if (sourceTables.isEmpty()) {
      return List.of();
    }
    return sourceTables.stream()
        .map(SyncRunPayload::trimToNull)
        .filter(value -> value != null)
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .filter(value -> !value.isBlank() && !"null".equals(value))
        .distinct()
        .toList();
  }

  public List<PreciseTarget> runnablePreciseTargets() {
    if (preciseTargets.isEmpty()) {
      return List.of();
    }
    return preciseTargets.stream()
        .map(PreciseTarget::normalized)
        .filter(PreciseTarget::runnable)
        .distinct()
        .toList();
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 一项按完整范围读取来源的精确同步目标。 */
  public record PreciseTarget(String tableName, Map<String, String> lookupScope) {
    public PreciseTarget {
      lookupScope = lookupScope == null ? Map.of() : Map.copyOf(lookupScope);
    }

    private PreciseTarget normalized() {
      String normalizedTable =
          tableName == null ? null : GitlabSourceInstanceSupport.normalizeSourceTableName(tableName);
      Map<String, String> normalizedScope = new java.util.TreeMap<>();
      lookupScope.forEach(
          (column, value) -> {
            String normalizedColumn = trimToNull(column);
            String normalizedValue = trimToNull(value);
            if (normalizedColumn != null && normalizedValue != null) {
              normalizedScope.put(normalizedColumn, normalizedValue);
            }
          });
      return new PreciseTarget(trimToNull(normalizedTable), normalizedScope);
    }

    private boolean runnable() {
      return usable(tableName)
          && !lookupScope.isEmpty()
          && lookupScope.entrySet().stream()
              .allMatch(entry -> usable(entry.getKey()) && usable(entry.getValue()));
    }

    private static boolean usable(String value) {
      return value != null && !value.isBlank() && !"null".equals(value);
    }
  }
}
