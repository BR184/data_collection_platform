package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabMirrorIndexServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private GitlabMirrorIndexService indexService;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("gitlab_mirror_index_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists ods_gitlab_issues cascade");
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issues (
          id bigint not null,
          iid bigint,
          project_id bigint,
          author_id bigint,
          milestone_id bigint,
          updated_at timestamp,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    indexService = new GitlabMirrorIndexService(jdbcTemplate);
  }

  @Test
  void test_new_dynamic_issue_table_gets_reverse_lookup_and_fact_indexes() {
    indexService.ensureIndexes("issues", "ods_gitlab_issues", issueSchema());

    List<String> indexNames =
        jdbcTemplate.queryForList(
            """
            select indexname
              from pg_indexes
             where schemaname = current_schema()
               and tablename = 'ods_gitlab_issues'
             order by indexname
            """,
            String.class);
    assertThat(indexNames)
        .contains(
            "idx_ods_gitlab_issues_project_root_active",
            "idx_ods_gitlab_issues_author_root_active",
            "idx_ods_gitlab_issues_milestone_root_active",
            "idx_ods_gitlab_issues_project_updated_active",
            "idx_ods_gitlab_issues_mirror_task");
  }

  @Test
  void test_existing_valid_index_is_reused_and_missing_indexes_are_added_once() {
    jdbcTemplate.execute(
        """
        create index idx_ods_gitlab_issues_project_root_active
            on ods_gitlab_issues(project_id, id)
            where mirror_deleted = false
        """);

    indexService.ensureIndexes("issues", "ods_gitlab_issues", issueSchema());
    indexService.ensureIndexes("issues", "ods_gitlab_issues", issueSchema());

    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from pg_indexes
             where schemaname = current_schema()
               and indexname = 'idx_ods_gitlab_issues_project_root_active'
            """,
            Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void test_project_and_user_reverse_lookup_indexes_are_created_on_dependency_tables() {
    createReverseLookupDependencyTables();

    indexService.ensureIndexes("issues", "ods_gitlab_issues", issueSchema());
    indexService.ensureIndexes(
        "merge_requests",
        "ods_gitlab_merge_requests",
        schema(
            "ods_gitlab_merge_requests",
            "id",
            "iid",
            "target_project_id",
            "author_id",
            "merge_user_id",
            "updated_at"));
    indexService.ensureIndexes(
        "issue_assignees",
        "ods_gitlab_issue_assignees",
        schema("ods_gitlab_issue_assignees", "issue_id", "user_id"));
    indexService.ensureIndexes(
        "merge_request_assignees",
        "ods_gitlab_merge_request_assignees",
        schema("ods_gitlab_merge_request_assignees", "merge_request_id", "user_id"));
    indexService.ensureIndexes(
        "merge_request_reviewers",
        "ods_gitlab_merge_request_reviewers",
        schema("ods_gitlab_merge_request_reviewers", "merge_request_id", "user_id"));
    indexService.ensureIndexes(
        "notes",
        "ods_gitlab_notes",
        schema(
            "ods_gitlab_notes",
            "noteable_type",
            "noteable_id",
            "author_id",
            "updated_at",
            "created_at"));

    List<String> indexNames =
        jdbcTemplate.queryForList(
            """
            select indexname
              from pg_indexes
             where schemaname = current_schema()
               and tablename in (
                   'ods_gitlab_issues',
                   'ods_gitlab_merge_requests',
                   'ods_gitlab_issue_assignees',
                   'ods_gitlab_merge_request_assignees',
                   'ods_gitlab_merge_request_reviewers',
                   'ods_gitlab_notes')
            """,
            String.class);
    assertThat(indexNames)
        .contains(
            "idx_ods_gitlab_issues_project_root_active",
            "idx_ods_gitlab_merge_requests_project_root_active",
            "idx_ods_gitlab_issues_author_root_active",
            "idx_ods_gitlab_issue_assignees_user_root_active",
            "idx_ods_gitlab_notes_author_root_active",
            "idx_ods_gitlab_merge_requests_author_root_active",
            "idx_ods_gitlab_merge_requests_merge_user_root_active",
            "idx_ods_gitlab_merge_request_assignees_user_root_active",
            "idx_ods_gitlab_merge_request_reviewers_user_root_active");
  }

  @Test
  void test_invalid_concurrent_index_is_replaced_with_valid_current_definition() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(id, project_id, mirror_deleted) values
          (1, 10, false),
          (1, 10, false)
        """);
    assertThatThrownBy(
            () ->
                jdbcTemplate.execute(
                    """
                    create unique index concurrently idx_ods_gitlab_issues_project_root_active
                        on ods_gitlab_issues(project_id, id)
                        where mirror_deleted = false
                    """))
        .isInstanceOf(DataAccessException.class);
    assertThat(indexValidity("idx_ods_gitlab_issues_project_root_active")).isFalse();

    indexService.ensureIndexes("issues", "ods_gitlab_issues", issueSchema());

    assertThat(indexValidity("idx_ods_gitlab_issues_project_root_active")).isTrue();
  }

  private void createReverseLookupDependencyTables() {
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_requests (
          id bigint,
          iid bigint,
          target_project_id bigint,
          author_id bigint,
          merge_user_id bigint,
          updated_at timestamp,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issue_assignees (
          issue_id bigint,
          user_id bigint,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_assignees (
          merge_request_id bigint,
          user_id bigint,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_reviewers (
          merge_request_id bigint,
          user_id bigint,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_notes (
          noteable_type varchar(32),
          noteable_id bigint,
          author_id bigint,
          updated_at timestamp,
          created_at timestamp,
          mirror_task_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
  }

  private boolean indexValidity(String indexName) {
    Boolean valid =
        jdbcTemplate.queryForObject(
            """
            select index_state.indisvalid
              from pg_index index_state
              join pg_class index_class on index_class.oid = index_state.indexrelid
             where index_class.relname = ?
            """,
            Boolean.class,
            indexName);
    return Boolean.TRUE.equals(valid);
  }

  private SourceTableSchema schema(String table, String... columns) {
    ArrayList<SourceTableColumn> sourceColumns = new ArrayList<>(columns.length);
    for (int index = 0; index < columns.length; index++) {
      sourceColumns.add(
          new SourceTableColumn(columns[index], "bigint", true, index + 1));
    }
    String updatedAt = sourceColumns.stream()
        .map(SourceTableColumn::columnName)
        .filter("updated_at"::equals)
        .findFirst()
        .orElse("");
    return new SourceTableSchema(table, List.of(columns[0]), updatedAt, sourceColumns);
  }

  private SourceTableSchema issueSchema() {
    return new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("iid", "bigint", true, 2),
            new SourceTableColumn("project_id", "bigint", true, 3),
            new SourceTableColumn("author_id", "bigint", true, 4),
            new SourceTableColumn("milestone_id", "bigint", true, 5),
            new SourceTableColumn("updated_at", "timestamp", true, 6)));
  }
}
