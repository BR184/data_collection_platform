package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IssueFactFixUserBackfillMigrationContractTest {
  private static final Path MIGRATION =
      Path.of(
          "src",
          "main",
          "resources",
          "db",
          "migration",
          "V20260710_01__backfill_issue_fact_fix_user.sql");

  @Test
  void shouldLimitHistoricalBackfillToDefaultIssueFactFixUser() throws Exception {
    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

    assertThat(sql)
        .contains("update issue_fact")
        .contains("set fix_user =")
        .contains("from ods_gitlab_notes")
        .contains("join ods_gitlab_users")
        .contains("join ods_gitlab_issues")
        .contains("source_instance = 'default'")
        .contains("is distinct from");
    assertThat(sql)
        .doesNotContain("delete from")
        .doesNotContain("truncate")
        .doesNotContain("set updated_at")
        .doesNotContain("merge_request_fact");
  }
}
