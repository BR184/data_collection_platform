package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GitlabFactRefreshRequirements {
  public static final String FACT_TYPE_ISSUE = "ISSUE";
  public static final String FACT_TYPE_MERGE_REQUEST = "MERGE_REQUEST";
  public static final String FACT_TYPE_INTEGRATION_TEST = "INTEGRATION_TEST";

  private static final List<String> ISSUE_FACT_REQUIRED_TABLES =
      List.of("issues", "projects", "users", "labels", "label_links", "notes", "issue_assignees");
  private static final List<String> MERGE_REQUEST_FACT_REQUIRED_TABLES =
      List.of(
          "merge_requests",
          "merge_request_metrics",
          "projects",
          "namespaces",
          "users",
          "merge_request_reviewers",
          "merge_request_assignees",
          "notes",
          "label_links",
          "labels");
  private static final List<String> INTEGRATION_TEST_FACT_REQUIRED_TABLES =
      List.of("issues", "projects", "users", "labels", "label_links", "notes");

  private GitlabFactRefreshRequirements() {
  }

  public static List<String> supportedFactTypes(GitlabSyncConfig config) {
    if (config == null || config.getWhitelistMode() != WhitelistMode.CUSTOM) {
      return List.of(FACT_TYPE_ISSUE, FACT_TYPE_MERGE_REQUEST, FACT_TYPE_INTEGRATION_TEST);
    }
    Set<String> whitelist = normalizedWhitelist(config);
    if (whitelist.isEmpty()) {
      return List.of();
    }
    List<String> supported = new java.util.ArrayList<>();
    if (whitelist.containsAll(ISSUE_FACT_REQUIRED_TABLES)) {
      supported.add(FACT_TYPE_ISSUE);
    }
    if (whitelist.containsAll(MERGE_REQUEST_FACT_REQUIRED_TABLES)) {
      supported.add(FACT_TYPE_MERGE_REQUEST);
    }
    if (whitelist.containsAll(INTEGRATION_TEST_FACT_REQUIRED_TABLES)) {
      supported.add(FACT_TYPE_INTEGRATION_TEST);
    }
    return List.copyOf(supported);
  }

  public static boolean supportsAnyFactRefresh(GitlabSyncConfig config) {
    return !supportedFactTypes(config).isEmpty();
  }

  public static List<String> allRequiredTables() {
    LinkedHashSet<String> tables = new LinkedHashSet<>();
    tables.addAll(ISSUE_FACT_REQUIRED_TABLES);
    tables.addAll(MERGE_REQUEST_FACT_REQUIRED_TABLES);
    tables.addAll(INTEGRATION_TEST_FACT_REQUIRED_TABLES);
    return List.copyOf(tables);
  }

  private static Set<String> normalizedWhitelist(GitlabSyncConfig config) {
    List<String> whitelist = config.getWhitelistTables();
    if (whitelist == null || whitelist.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String table : whitelist) {
      String value = GitlabSourceInstanceSupport.normalizeSourceTableName(table);
      if (!value.isBlank()) {
        normalized.add(value);
      }
    }
    return normalized;
  }
}
