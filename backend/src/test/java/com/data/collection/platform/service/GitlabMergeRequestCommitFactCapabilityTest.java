package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabMergeRequestCommitFactCapabilityTest {

  @Test
  void test_recommended_source_enables_merge_request_commit_facts() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);

    assertThat(GitlabMergeRequestCommitFactCapability.isEnabled(config)).isTrue();
  }

  @Test
  void test_custom_source_requires_both_commit_lineage_tables() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("merge_requests", "merge_request_diffs"));

    assertThat(GitlabMergeRequestCommitFactCapability.isEnabled(config)).isFalse();

    config.setWhitelistTables(
        List.of("merge_requests", "merge_request_diffs", "merge_request_diff_commits"));

    assertThat(GitlabMergeRequestCommitFactCapability.isEnabled(config)).isTrue();
  }

  @Test
  void test_custom_source_normalizes_configured_source_table_names() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(
        List.of(" Merge_Request_Diffs ", "MERGE_REQUEST_DIFF_COMMITS"));

    assertThat(GitlabMergeRequestCommitFactCapability.isEnabled(config)).isTrue();
  }
}
