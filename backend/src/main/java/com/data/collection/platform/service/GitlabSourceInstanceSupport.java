package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class GitlabSourceInstanceSupport {
  public static final String DEFAULT_SOURCE_INSTANCE = "default";
  public static final int MAX_SOURCE_INSTANCE_LENGTH = 64;
  private static final String MIRROR_PREFIX = "ods_gitlab_";
  private static final int POSTGRES_IDENTIFIER_MAX_LENGTH = 63;

  private GitlabSourceInstanceSupport() {}

  public static String sourceInstanceOf(GitlabSyncConfig config) {
    return config == null ? DEFAULT_SOURCE_INSTANCE : normalizeSourceInstance(config.getSourceInstance());
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

  public static String buildMirrorTableName(String sourceTableName, String sourceInstance) {
    String normalizedSourceTable = normalizeSourceTableName(sourceTableName);
    String normalizedSource = normalizeSourceInstance(sourceInstance);
    String logicalName =
        DEFAULT_SOURCE_INSTANCE.equals(normalizedSource)
            ? normalizedSourceTable
            : normalizedSource + "_" + normalizedSourceTable;
    String candidate = MIRROR_PREFIX + logicalName;
    if (candidate.length() <= POSTGRES_IDENTIFIER_MAX_LENGTH) {
      return candidate;
    }
    String hash = shortHash(normalizedSource + ":" + normalizedSourceTable);
    int keepLength = POSTGRES_IDENTIFIER_MAX_LENGTH - MIRROR_PREFIX.length() - hash.length() - 1;
    return MIRROR_PREFIX + logicalName.substring(0, Math.max(8, keepLength)) + "_" + hash;
  }

  public static String rewriteMirrorTableReferences(String sql, String sourceInstance) {
    String normalizedSource = normalizeSourceInstance(sourceInstance);
    if (DEFAULT_SOURCE_INSTANCE.equals(normalizedSource)) {
      return sql;
    }
    return sql.replaceAll("\\bods_gitlab_([a-z0-9_]+)\\b", "ods_gitlab_" + normalizedSource + "_$1");
  }

  static String shortHash(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to hash GitLab source identifier", e);
    }
  }
}
