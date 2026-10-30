package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.time.LocalDateTime;
import java.util.List;
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
  void test_full_and_root_scoped_sources_use_the_latest_resolution_plan_even_when_comment_times_are_equal() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, note, author_id, created_at, mirror_deleted) values
          (1000, 1, 'Issue', '# 问题调研情况说明' || chr(10)
             || '## 计划解决时间：2026.01.05', 100, timestamp '2026-01-04 08:00:00', false),
          (1001, 1, 'Issue', '# 问题调研情况说明' || chr(10)
             || '## 计划解决时间：2026.01.15', 100, timestamp '2026-01-04 08:00:00', false)
        """);

    for (boolean rootScoped : List.of(false, true)) {
      String sourceSql = rootScoped
          ? provider.issueSourceSqlForRoots(List.of(1L)) : provider.issueSourceSql();
      Object[] arguments = rootScoped ? new Object[] {1L} : new Object[0];
      List<LocalDateTime> deadlines = jdbcTemplate.query(
          "select * from (" + sourceSql + ") source_row where issue_id = 1",
          (rs, rowNum) -> IssueFactNormalizationRules.resolveDeadline(
              rs.getTimestamp("created_at").toLocalDateTime(), rs.getString("notes_text")),
          arguments);

      assertThat(deadlines)
          .as("全量与定向来源均按创建时间及ID从新到旧提供解决计划")
          .containsExactly(LocalDateTime.of(2026, 1, 15, 0, 0));
    }
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
  void test_customer_project_completion_events_require_currently_linked_equivalent_labels() {
    insertCustomerProjectFixture();

    List<String> rows = rows(provider.issueSourceSql(), List.of());
    String bare = row(rows, 5L);
    String mixed = row(rows, 6L);
    String missingEvent = row(rows, 7L);
    String fullWidth = row(rows, 8L);

    assertThat(bare)
        .contains("\"fixed_label_time\":\"2026-02-01T10:00:00\"")
        .as("tab 空白行后的规范表头也必须被首次响应时间 SQL 识别")
        .contains("\"research_template_time\":\"2026-02-02T08:30:00\"");
    assertThat(mixed)
        .as("已撤标标签的后续 add 事件不能伪造当前完成时间；既有SQL按含#表头匹配，##标题也命中")
        .contains("\"fixed_label_time\":\"2026-02-03T11:00:00\"")
        .contains("\"research_template_time\":\"2026-02-03T08:00:00\"");
    assertThat(missingEvent)
        .as("无有效 add 事件保持未知，不退化为关联时间或更新时间")
        .contains("\"fixed_label_time\":null");
    assertThat(fullWidth).contains("\"fixed_label_time\":\"2026-02-01T11:00:00\"");

    assertThat(row(rows, 9L))
        .as("普通标签改名为完成后，改名前的 add 不能证明事件时语义")
        .contains("\"fixed_label_time\":null");
    assertThat(row(rows, 10L))
        .as("改名后出现新的 add 才可证明完成语义")
        .contains("\"fixed_label_time\":\"2026-02-11T10:00:00\"");
    assertThat(row(rows, 11L))
        .as("等价完成词之间改名且无新 add 时保持未知")
        .contains("\"fixed_label_time\":null");
    assertThat(row(rows, 12L))
        .as("等价完成词改名后的新 add 可证明完成语义")
        .contains("\"fixed_label_time\":\"2026-02-12T10:00:00\"");
    assertThat(row(rows, 13L))
        .as("完成→普通→完成且无新 add 时保持未知")
        .contains("\"fixed_label_time\":null");
    assertThat(row(rows, 14L))
        .as("完成→普通→完成后的新 add 可证明完成语义")
        .contains("\"fixed_label_time\":\"2026-02-13T10:00:00\"");

    List<Long> roots = List.of(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L);
    List<String> scoped = rows(provider.issueSourceSqlForRoots(roots), roots);
    assertThat(scoped)
        .containsExactly(bare, mixed, missingEvent, fullWidth,
            row(rows, 9L), row(rows, 10L), row(rows, 11L), row(rows, 12L),
            row(rows, 13L), row(rows, 14L));
  }

  private String row(List<String> rows, long issueId) {
    return rows.stream()
        .filter(row -> row.contains("\"issue_id\":" + issueId))
        .findFirst()
        .orElseThrow();
  }

  private void insertCustomerProjectFixture() {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (325, 'CC_Product', false)");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_labels(id, title, updated_at, mirror_deleted) values
          (210, '已修复/完成', timestamp '2026-02-01 00:00:00', false),
          (211, '状态:已修复/完成', timestamp '2026-02-01 00:00:00', false),
          (212, '状态：已修复/完成', timestamp '2026-02-01 00:00:00', false),
          (213, '其他客户标签', timestamp '2026-02-01 00:00:00', false),
          (214, '已修复/完成', timestamp '2026-02-10 00:00:00', false),
          (215, '状态:已修复/完成', timestamp '2026-02-10 00:00:00', false),
          (216, '已修复/完成', timestamp '2026-02-10 00:00:00', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
            id, iid, project_id, milestone_id, title, description, author_id,
            created_at, updated_at, closed_at, state_id, mirror_deleted) values
          (5, 15, 325, null, '裸完成标签', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-04 08:00:00', null, 1, false),
          (6, 16, 325, null, '撤标后重加', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-04 08:00:00', null, 1, false),
          (7, 17, 325, null, '只有关联无事件', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-04 08:00:00', null, 1, false),
          (8, 18, 325, null, '全角完成标签', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-04 08:00:00', null, 1, false),
          (9, 19, 325, null, '普通改完成且无新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-10 00:00:00', null, 1, false),
          (10, 20, 325, null, '普通改完成且有新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-11 10:00:00', null, 1, false),
          (11, 21, 325, null, '等价完成词改名且无新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-10 00:00:00', null, 1, false),
          (12, 22, 325, null, '等价完成词改名且有新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-12 10:00:00', null, 1, false),
          (13, 23, 325, null, '完成改普通再改完成且无新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-10 00:00:00', null, 1, false),
          (14, 24, 325, null, '完成改普通再改完成且有新add', null, 100,
           timestamp '2026-02-01 08:00:00', timestamp '2026-02-13 10:00:00', null, 1, false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(target_id, target_type, label_id, mirror_deleted) values
          (5, 'Issue', 210, false),
          (6, 'Issue', 210, false),
          (7, 'Issue', 210, false),
          (8, 'Issue', 212, false),
          (9, 'Issue', 214, false),
          (10, 'Issue', 214, false),
          (11, 'Issue', 215, false),
          (12, 'Issue', 215, false),
          (13, 'Issue', 216, false),
          (14, 'Issue', 216, false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, note, author_id, created_at, mirror_deleted) values
          (500, 5, 'Issue', '正文提及问题调研情况说明，但不是标题' || chr(10)
             || '## 计划解决时间：2026.01.01', 100, timestamp '2026-02-02 08:00:00', false),
          (501, 5, 'Issue', '问题调研情况说明' || chr(10) || '## 计划解决时间：2026.02.02',
           100, timestamp '2026-02-02 09:00:00', false),
          (502, 6, 'Issue', '## 问题调研情况说明' || chr(10) || '## 计划解决时间：2026.02.01',
           100, timestamp '2026-02-03 08:00:00', false),
          (503, 6, 'Issue', '# 问题调研情况说明' || chr(10) || '## 计划解决时间：2026.02.03',
           100, timestamp '2026-02-03 09:00:00', false),
          (504, 7, 'Issue', '普通评论', 100, timestamp '2026-02-03 10:00:00', false),
          (505, 5, 'Issue', E'\\t\\n# 问题调研情况说明\\n## 计划解决时间：2026.02.10', 100,
           timestamp '2026-02-02 08:30:00', false)
        """);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_resource_label_events(
            issue_id, label_id, action, created_at, mirror_deleted) values
          (5, 210, 1, timestamp '2026-02-01 10:00:00', false),
          (6, 212, 1, timestamp '2026-02-02 10:00:00', false),
          (6, 210, 1, timestamp '2026-02-03 11:00:00', false),
          (6, 212, 1, timestamp '2026-02-04 12:00:00', false),
          (8, 212, 1, timestamp '2026-02-01 11:00:00', false),
          (9, 214, 1, timestamp '2026-02-01 10:00:00', false),
          (10, 214, 1, timestamp '2026-02-01 10:00:00', false),
          (10, 214, 1, timestamp '2026-02-11 10:00:00', false),
          (11, 215, 1, timestamp '2026-02-01 10:00:00', false),
          (12, 215, 1, timestamp '2026-02-01 10:00:00', false),
          (12, 215, 1, timestamp '2026-02-12 10:00:00', false),
          (13, 216, 1, timestamp '2026-02-01 10:00:00', false),
          (14, 216, 1, timestamp '2026-02-01 10:00:00', false),
          (14, 216, 1, timestamp '2026-02-13 10:00:00', false)
        """);
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

  private List<String> rows(String sql, List<?> arguments) {
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
        insert into ods_gitlab_labels(id, title, updated_at, mirror_deleted) values
          (200, '状态：已修复/完成', timestamp '2026-01-01 00:00:00', false),
          (201, '缺陷', timestamp '2026-01-01 00:00:00', false),
          (202, '严重', timestamp '2026-01-01 00:00:00', false),
          (203, '其他', timestamp '2026-01-01 00:00:00', false)
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
          updated_at timestamp,
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
