package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 判断当前 GitLab 同步配置是否具备 MR 提交事实增强所需的完整来源。 */
public final class GitlabMergeRequestCommitFactCapability {
  private static final List<String> REQUIRED_SOURCE_TABLES =
      List.of("merge_request_diffs", "merge_request_diff_commits");

  private GitlabMergeRequestCommitFactCapability() {}

  /**
   * 判断配置是否会同步提交事实所需的两张血缘表。
   *
   * <p>推荐和全部模式由目录保证包含两张表；自定义模式必须显式同时选择两张表。
   *
   * @param config 当前 GitLab 同步配置
   * @return 来源能够构建 {@code merge_request_commit_fact} 时为 {@code true}
   */
  public static boolean isEnabled(GitlabSyncConfig config) {
    if (config == null || config.getWhitelistMode() != WhitelistMode.CUSTOM) {
      return true;
    }
    List<String> configuredTables = config.getWhitelistTables();
    if (configuredTables == null || configuredTables.isEmpty()) {
      return false;
    }
    Set<String> normalizedTables = new LinkedHashSet<>();
    configuredTables.stream()
        .filter(table -> table != null && !table.isBlank())
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .forEach(normalizedTables::add);
    return normalizedTables.containsAll(REQUIRED_SOURCE_TABLES);
  }
}
