package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GitlabFactSourceQueryExecutorTest {
  private final GitlabFactSourceQueryExecutor executor = new GitlabFactSourceQueryExecutor(null, null);

  @Test
  void shouldRewriteMirrorTablesForNamedSourceInstances() {
    String sql = executor.buildSourceSql(
        "select * from ods_gitlab_issues i join ods_gitlab_merge_requests mr on mr.id = i.id",
        "DGM-InnerNet",
        "and coalesce(i.updated_at, i.created_at) > ?",
        null);

    assertThat(sql)
        .contains("ods_gitlab_dgm_innernet_issues")
        .contains("ods_gitlab_dgm_innernet_merge_requests")
        .doesNotContain("coalesce(i.updated_at, i.created_at) > ?");
  }

  @Test
  void shouldAppendIncrementalPredicateOnlyWhenChangedSinceExists() {
    String sql = executor.buildSourceSql(
        "select * from ods_gitlab_issues i where coalesce(i.mirror_deleted, false) = false",
        "default",
        "and coalesce(i.updated_at, i.created_at) > ?",
        LocalDateTime.of(2026, 5, 28, 10, 0));

    assertThat(sql)
        .isEqualTo("select * from ods_gitlab_issues i where coalesce(i.mirror_deleted, false) = false "
            + "and coalesce(i.updated_at, i.created_at) > ?");
  }
}
