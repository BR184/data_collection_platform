package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.IntegrationTestDetailResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class IntegrationTestFactPipelineTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private IntegrationTestFactBuildService factBuildService;

  @Autowired private IntegrationTestQueryService queryService;

  @BeforeEach
  void setUp() {
    createMinimalOdsTables();
    cleanTables();
  }

  @Test
  void shouldBuildFactsFromGitlabNotesAndReturnSummaryAndDetails() {
    insertIssueWithHorizontalIntegrationNote();

    FactBuildResponse buildResponse = factBuildService.rebuildFacts(true);
    IntegrationTestSummaryResponse summary = queryService.getSummary(325L, "R1集成测试");
    IntegrationTestDetailResponse details =
        queryService.getDetails(325L, "R1集成测试", "草图", 1, 20, "noteUpdatedAt", "desc");

    assertThat(buildResponse.affectedRows()).isEqualTo(1);
    assertThat(summary.totalIssueCount()).isEqualTo(1);
    assertThat(summary.rows()).hasSize(1);
    assertThat(summary.rows().getFirst().moduleName()).isEqualTo("草图");
    assertThat(summary.rows().getFirst().executeCase()).isEqualTo(10);
    assertThat(summary.rows().getFirst().passCase()).isEqualTo(8);
    assertThat(summary.rows().getFirst().notPassCaseNow()).isEqualTo(2);
    assertThat(summary.rows().getFirst().passRate()).isEqualByComparingTo("80.00");
    assertThat(summary.rows().getFirst().illegalCount()).isZero();

    assertThat(details.records()).hasSize(1);
    assertThat(details.records().getFirst().issuableReference()).isEqualTo("#88");
    assertThat(details.records().getFirst().title()).isEqualTo("集成测试横向表格样例");
    assertThat(details.records().getFirst().functionName()).isEqualTo("拉伸");
    assertThat(details.records().getFirst().executor()).isEqualTo("张三");
    assertThat(details.records().getFirst().legal()).isTrue();
    assertThat(details.records().getFirst().parseStatus()).isEqualTo("PARSED");
  }

  private void createMinimalOdsTables() {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_users (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issues (
          id bigint primary key,
          iid bigint,
          project_id bigint,
          title varchar(512),
          author_id bigint,
          created_at timestamp,
          updated_at timestamp,
          closed_at timestamp,
          state_id integer,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_notes (
          id bigint primary key,
          noteable_id bigint,
          noteable_type varchar(64),
          note text,
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_labels (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_label_links (
          label_id bigint,
          target_id bigint,
          target_type varchar(64),
          mirror_deleted boolean default false
        )
        """);
  }

  private void cleanTables() {
    jdbcTemplate.update("delete from integration_test_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from testing_phase_calendar");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
  }

  private void insertIssueWithHorizontalIntegrationNote() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 24, 9, 0);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 4, 24, 10, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        325L,
        "CC_PRODUCT");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        501L,
        "李四");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, mirror_deleted
        ) values (?, ?, ?, ?, ?, ?, ?, null, ?, false)
        """,
        1001L,
        88L,
        325L,
        "集成测试横向表格样例",
        501L,
        createdAt,
        updatedAt,
        1);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(id, noteable_id, noteable_type, note, created_at, updated_at, mirror_deleted)
        values (?, ?, 'Issue', ?, ?, ?, false)
        """,
        7001L,
        1001L,
        """
        ## 集成测试数据
        | 功能 | 执行人 | 执行用例总数 | 本次通过用例数 | 初始未通过用例数 | 本次未通过用例数 | 本次问题用例数 | 用例外问题数 |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | 拉伸 | 张三 | 10 | 8 | 2 | 2 | 1 | 0 |
        """,
        createdAt.plusMinutes(30),
        updatedAt);
    insertLabel(1L, "草图模块");
    insertLabel(2L, "R1集成测试");
    insertLabel(3L, "新功能");
    linkLabel(1L, 1001L);
    linkLabel(2L, 1001L);
    linkLabel(3L, 1001L);
  }

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        id,
        title);
  }

  private void linkLabel(long labelId, long issueId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(label_id, target_id, target_type, mirror_deleted)
        values (?, ?, 'Issue', false)
        """,
        labelId,
        issueId);
  }
}
