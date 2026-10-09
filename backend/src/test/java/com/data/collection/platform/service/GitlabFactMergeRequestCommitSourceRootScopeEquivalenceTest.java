package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 合并请求提交来源 SQL 的根集合收窄等价性。
 *
 * <p>全量构建装载全部提交关系，定向发布只装载指定稳定根；两者必须只在"作用范围"上不同、在口径上完全一致。
 * 本测试在同一份夹具上对拍"读全量后过滤"与"先按根集合收窄"，要求根集合内逐行逐列一致；同时锁定未收窄变体
 * 不携带根作用域、按根变体保留最新 Diff 口径且拒绝空根集合。缺少本对拍正是"全量构建装载到零条提交关系"
 * 长期未被发现的原因。
 */
class GitlabFactMergeRequestCommitSourceRootScopeEquivalenceTest {
  private static PostgresIntegrationTestDatabase database;

  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();
  private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("mr_commit_root_scope_equivalence_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(database.dataSource());
    dropSchema();
    createSchema();
    seedFixture();
  }

  @Test
  void test_full_and_root_scoped_commit_sql_return_identical_rows_for_the_root_set() {
    List<String> full = rows(provider.mergeRequestCommitSourceSql(), List.of());
    List<String> scoped =
        rows(provider.mergeRequestCommitSourceSqlForRoots(List.of(1L, 2L)), List.of(1L, 2L));

    assertThat(full)
        .as("夹具必须让全量口径恰好产出三行（两个有效 MR 的最新 Diff 提交），否则对拍无意义")
        .hasSize(3);
    assertThat(full)
        .as("全量口径与定向口径在根集合内必须逐行逐列一致")
        .filteredOn(row -> row.contains("\"merge_request_id\":1") || row.contains("\"merge_request_id\":2"))
        .containsExactlyElementsOf(scoped);
    assertThat(scoped).hasSize(3);
  }

  @Test
  void test_root_scoped_commit_sql_keeps_only_the_requested_root_latest_diff_commits() {
    List<String> scoped =
        rows(provider.mergeRequestCommitSourceSqlForRoots(List.of(1L)), List.of(1L));

    assertThat(scoped)
        .as("只保留该根最新 Diff 的提交：旧 Diff 的提交、已删除提交与缺提交时间的提交都必须剔除")
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row).contains("\"merge_request_id\":1");
              assertThat(row).contains("\"project_id\":10");
            })
        .noneSatisfy(row -> assertThat(row).contains("\"commit_sha\":\"000000cc\""));
  }

  @Test
  void test_root_scoped_commit_sql_rejects_an_empty_root_set() {
    assertThatThrownBy(() -> provider.mergeRequestCommitSourceSqlForRoots(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("稳定根集合");
  }

  @Test
  void test_commit_sql_variants_contain_no_unresolved_placeholder() {
    assertThat(provider.mergeRequestCommitSourceSql()).doesNotContain("__");
    assertThat(provider.mergeRequestCommitSourceSqlForRoots(List.of(1L))).doesNotContain("__");
  }

  private List<String> rows(String sql, List<Object> arguments) {
    return jdbcTemplate.queryForList(
        "select row_to_json(source_row)::text as row_json from (" + sql + ") source_row"
            + " order by source_row.merge_request_id, source_row.commit_sha",
        String.class,
        arguments.toArray());
  }

  private void seedFixture() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_merge_requests(id, iid, target_project_id, mirror_deleted) values
          (1, 11, 10, false),
          (2, 12, 10, false),
          (3, 13, 10, true)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_merge_request_diffs(
            id, merge_request_id, created_at, updated_at, mirror_deleted) values
          (100, 1, timestamp '2026-01-01 08:00:00', timestamp '2026-01-01 08:00:00', false),
          (101, 1, timestamp '2026-01-05 08:00:00', timestamp '2026-01-05 08:00:00', false),
          (200, 2, timestamp '2026-01-02 08:00:00', timestamp '2026-01-02 08:00:00', false),
          (300, 3, timestamp '2026-01-03 08:00:00', timestamp '2026-01-03 08:00:00', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_merge_request_diff_commits(
            merge_request_diff_id, sha, committed_date, mirror_deleted) values
          (101, decode('aa', 'hex'), timestamp '2026-01-06 08:00:00', false),
          (101, decode('bb', 'hex'), timestamp '2026-01-07 08:00:00', false),
          (100, decode('cc', 'hex'), timestamp '2026-01-02 08:00:00', false),
          (101, decode('dd', 'hex'), timestamp '2026-01-08 08:00:00', true),
          (101, decode('ee', 'hex'), null, false),
          (200, decode('ff', 'hex'), timestamp '2026-01-03 08:00:00', false),
          (300, decode('11', 'hex'), timestamp '2026-01-04 08:00:00', false)
        """);
  }

  private void dropSchema() {
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_request_diff_commits cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_request_diffs cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_requests cascade");
  }

  private void createSchema() {
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_requests (
          id integer primary key,
          iid integer,
          target_project_id integer,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_diffs (
          id integer primary key,
          merge_request_id integer,
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_diff_commits (
          merge_request_diff_id integer,
          sha bytea,
          committed_date timestamp,
          mirror_deleted boolean
        )
        """);
  }
}
