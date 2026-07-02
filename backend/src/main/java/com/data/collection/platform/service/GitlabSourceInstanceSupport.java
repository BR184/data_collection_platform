package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.Locale;

public final class GitlabSourceInstanceSupport {
  public static final String DEFAULT_SOURCE_INSTANCE = "default";
  private static final String MIRROR_PREFIX = "ods_gitlab_";

  private GitlabSourceInstanceSupport() {}

  public static String sourceInstanceOf(GitlabSyncConfig config) {
    return DEFAULT_SOURCE_INSTANCE;
  }

  public static String normalizeSourceInstance(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_SOURCE_INSTANCE;
    }
    String normalized =
        raw.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
    return normalized.isBlank() ? DEFAULT_SOURCE_INSTANCE : normalized;
  }

  public static String normalizeSourceTableName(String sourceTableName) {
    if (sourceTableName == null || sourceTableName.isBlank()) {
      throw new IllegalArgumentException("sourceTableName must not be blank");
    }
    String normalized =
        sourceTableName.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("sourceTableName must contain at least one identifier character");
    }
    return normalized;
  }

  public static String buildMirrorTableName(String sourceTableName) {
    String normalizedSourceTable = normalizeSourceTableName(sourceTableName);
    return MIRROR_PREFIX + normalizedSourceTable;
  }
}
