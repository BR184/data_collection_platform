package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.service.GitlabFactChangeResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabFactChangeResolverIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private GitlabFactChangeResolver resolver;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("gitlab_fact_change_resolver_test");
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
    resetSchema();
    resolver = new GitlabFactChangeResolver(jdbcTemplate);
  }

  @Test
  void test_user_change_resolves_all_issue_and_merge_request_roles_without_duplicates() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(id, project_id, iid, author_id) values
          (101, 10, 1, 20),
          (102, 10, 2, 30),
          (103, 11, 3, 30)
        """);
    jdbcTemplate.update(
        "insert into ods_gitlab_issue_assignees(issue_id, user_id) values (102, 20)");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_merge_requests(
            id, target_project_id, iid, author_id, merge_user_id) values
          (201, 10, 4, 20, null),
          (202, 11, 5, 30, 20)
        """);
    jdbcTemplate.update(
        "insert into ods_gitlab_merge_request_assignees(merge_request_id, user_id) values (202, 20)");
    jdbcTemplate.update(
        "insert into ods_gitlab_merge_request_reviewers(merge_request_id, user_id) values (202, 20)");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(noteable_id, noteable_type, author_id) values
          (103, 'Issue', 20),
          (202, 'MergeRequest', 20)
        """);

    List<FactChangeIdentity> identities =
        resolver.resolve(
            "alpha",
            "users",
            List.of(new MirrorRowChange(Map.of("id", 20L), Map.of("id", 20L))));

    assertThat(identities)
        .filteredOn(identity -> identity.factType() == FactType.ISSUE)
        .extracting(FactChangeIdentity::rootId)
        .containsExactly(101L, 102L, 103L);
    assertThat(identities)
        .filteredOn(identity -> identity.factType() == FactType.INTEGRATION_TEST)
        .extracting(FactChangeIdentity::rootId)
        .containsExactly(101L, 102L, 103L);
    assertThat(identities)
        .filteredOn(identity -> identity.factType() == FactType.MERGE_REQUEST)
        .extracting(FactChangeIdentity::rootId)
        .containsExactly(201L, 202L);
  }

  @Test
  void test_project_change_loads_root_details_across_internal_batch_boundary() {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(id, project_id, iid, author_id)
        select value, 10, value, 20
          from generate_series(1, 1001) value
        """);

    List<FactChangeIdentity> identities =
        resolver.resolve(
            "alpha",
            "projects",
            List.of(new MirrorRowChange(Map.of(), Map.of("id", 10L))));

    assertThat(identities)
        .filteredOn(identity -> identity.factType() == FactType.ISSUE)
        .hasSize(1001)
        .allSatisfy(identity -> assertThat(identity.projectId()).isEqualTo(10L));
    assertThat(identities)
        .filteredOn(identity -> identity.factType() == FactType.INTEGRATION_TEST)
        .hasSize(1001)
        .allSatisfy(identity -> assertThat(identity.projectId()).isEqualTo(10L));
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_request_reviewers");
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_request_assignees");
    jdbcTemplate.execute("drop table if exists ods_gitlab_issue_assignees");
    jdbcTemplate.execute("drop table if exists ods_gitlab_notes");
    jdbcTemplate.execute("drop table if exists ods_gitlab_merge_requests");
    jdbcTemplate.execute("drop table if exists ods_gitlab_issues");
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issues (
          id bigint primary key,
          project_id bigint,
          iid bigint,
          author_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_requests (
          id bigint primary key,
          target_project_id bigint,
          iid bigint,
          author_id bigint,
          merge_user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_issue_assignees (
          issue_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_assignees (
          merge_request_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_merge_request_reviewers (
          merge_request_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table ods_gitlab_notes (
          noteable_id bigint,
          noteable_type varchar(32),
          author_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
  }
}
