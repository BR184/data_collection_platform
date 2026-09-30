package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 议题来源 SQL 的根集合收窄等价性。
 *
 * <p>定向发布把根集合下推到每个聚合子查询；本测试在同一份夹具上对拍"读全量后过滤"与"先按根集合收窄"
 * 两种口径，要求逐行逐列完全一致。语义等价的前提是每个被收窄的子查询都按稳定根分组、且只通过该键与
 * 最终查询连接，因此任何一行的差异都说明收窄破坏了口径。
 */
class GitlabFactIssueSourceRootScopeEquivalenceTest {
  private static PostgresIntegrationTestDatabase database;

  private final GitlabFactSourceSqlProvider provider = new GitlabFactSourceSqlProvider();
  private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("issue_root_scope_equivalence_test");
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
  void test_full_and_root_scoped_issue_sql_return_identical_rows_for_the_root_set() {
    List<String> full = rows(provider.issueSourceSql(), List.of());
    List<String> scoped = rows(provider.issueSourceSqlForRoots(List.of(1L, 2L)), List.of(1L, 2L));

    assertThat(full).as("夹具必须让全量口径至少产出三行，否则对拍无意义").hasSize(3);
    assertThat(full)
        .as("全量口径与定向口径在根集合内必须逐行逐列一致")
        .filteredOn(row -> row.contains("\"issue_id\":1") || row.contains("\"issue_id\":2"))
        .containsExactlyElementsOf(scoped);
    assertThat(scoped).hasSize(2);
  }

  @Test
  void test_root_scoped_issue_sql_keeps_aggregates_of_each_root_intact() {
    List<String> scoped = rows(provider.issueSourceSqlForRoots(List.of(1L)), List.of(1L));

    assertThat(scoped).hasSize(1);
    assertThat(scoped.getFirst())
        .as("标签、处理人、评论与资源标签事件都必须来自该根自身的数据")
        .contains("\"label_titles\":[\"严重\",\"缺陷\"]")
        .contains("\"assignee_names\":\"李四\"")
        .contains("\"fix_user\":\"张三\"")
        .contains("\"fixed_label_time\":\"2026-01-02T11:00:00\"");
  }

  @Test
  void test_root_scoped_issue_sql_rejects_an_empty_root_set() {
    assertThatThrownBy(() -> provider.issueSourceSqlForRoots(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("稳定根集合");
  }

  @Test
  void test_full_issue_sql_contains_no_unresolved_placeholder() {
    assertThat(provider.issueSourceSql()).doesNotContain("__");
    assertThat(provider.issueSourceSqlForRoots(List.of(1L))).doesNotContain("__");
  }

  private List<String> rows(String sql, List<Object> arguments) {
    return jdbcTemplate.queryForList(
        "select row_to_json(source_row)::text as row_json from (" + sql + ") source_row"
            + " order by source_row.issue_id",
        String.class,
        arguments.toArray());
  }

  private void seedFixture() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_projects(id, name, mirror_deleted) values (10, 'P10', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_users(id, name, mirror_deleted) values
          (100, '张三', false),
          (101, '李四', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_labels(id, title, mirror_deleted) values
          (200, '状态：已修复/完成', false),
          (201, '缺陷', false),
          (202, '严重', false),
          (203, '其他', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_milestones(id, project_id, title, mirror_deleted)
        values (300, 10, 'M1', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
            id, iid, project_id, milestone_id, title, description, author_id,
            created_at, updated_at, closed_at, state_id, mirror_deleted) values
          (1, 11, 10, 300, '议题一', '描述一', 100,
           timestamp '2026-01-01 08:00:00', timestamp '2026-01-02 08:00:00', null, 1, false),
          (2, 12, 10, null, '议题二', null, 101,
           timestamp '2026-01-01 09:00:00', timestamp '2026-01-03 09:00:00', null, 1, false),
          (3, 13, 10, null, '已删除议题', null, 101,
           timestamp '2026-01-01 10:00:00', timestamp '2026-01-04 10:00:00', null, 1, true),
          (4, 14, 10, null, '议题四', null, 100,
           timestamp '2026-01-01 11:00:00', timestamp '2026-01-05 11:00:00', null, 1, false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(target_id, target_type, label_id, mirror_deleted) values
          (1, 'Issue', 201, false),
          (1, 'Issue', 202, false),
          (2, 'Issue', 203, false),
          (2, 'MergeRequest', 201, false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issue_assignees(issue_id, user_id, mirror_deleted)
        values (1, 101, false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, note, author_id, created_at, mirror_deleted) values
          (400, 1, 'Issue', '### 1、修复状态' || chr(10) || '已修复', 100,
           timestamp '2026-01-02 09:00:00', false),
          (401, 1, 'Issue', '# 问题调研情况说明', 100,
           timestamp '2026-01-02 10:00:00', false),
          (402, 2, 'Issue', '普通评论', 101, timestamp '2026-01-03 09:00:00', false),
          (403, 2, 'MergeRequest', '不应计入议题评论', 101,
           timestamp '2026-01-03 10:00:00', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_resource_label_events(
            issue_id, label_id, action, created_at, mirror_deleted) values
          (1, 200, 1, timestamp '2026-01-02 11:00:00', false),
          (2, 200, 1, timestamp '2026-01-03 11:00:00', false)
        """);
  }

  private void dropSchema() {
    jdbcTemplate.execute("drop table if exists ods_gitlab_resource_label_events cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_notes cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_issue_assignees cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_label_links cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_labels cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_milestones cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_issues cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_users cascade");
    jdbcTemplate.execute("drop table if exists ods_gitlab_projects cascade");
  }

  private void createSchema() {
    jdbcTemplate.execute(
        """
        create table ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_users (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_labels (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_milestones (
          id bigint primary key,
          project_id bigint,
          title varchar(255),
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issues (
          id bigint primary key,
          iid bigint,
          project_id bigint,
          milestone_id bigint,
          title varchar(255),
          description text,
          author_id bigint,
          created_at timestamp,
          updated_at timestamp,
          closed_at timestamp,
          state_id bigint,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_label_links (
          target_id bigint,
          target_type varchar(64),
          label_id bigint,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issue_assignees (
          issue_id bigint,
          user_id bigint,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_notes (
          id bigint primary key,
          noteable_id bigint,
          noteable_type varchar(64),
          note text,
          author_id bigint,
          created_at timestamp,
          mirror_deleted boolean
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_resource_label_events (
          issue_id bigint,
          label_id bigint,
          action integer,
          created_at timestamp,
          mirror_deleted boolean
        )
        """);
  }
}
