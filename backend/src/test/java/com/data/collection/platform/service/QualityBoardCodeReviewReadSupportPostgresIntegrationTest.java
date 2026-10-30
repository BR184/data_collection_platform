package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 质量看板代码走查版本范围在真实 PostgreSQL 上的行为回归。 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QualityBoardCodeReviewReadSupportPostgresIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private JdbcTemplate jdbcTemplate;
  private QualityBoardCodeReviewReadSupport support;

  @BeforeAll
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    support = new QualityBoardCodeReviewReadSupport(
        jdbcTemplate, mock(CodeReviewMatchModeSwitchService.class));
  }

  @BeforeEach
  void cleanFacts() {
    jdbcTemplate.update("delete from code_review_match_mode_records");
    jdbcTemplate.update("delete from merge_request_fact");
  }

  @Test
  void formalModeSeparatesSourcesAndSupportsCombinationSpAndCustomScopes() {
    insertFormal("cc", "CC2027R1&R2", "dev", 1, 3, 100);
    insertFormal("cc", "CC2026R4SP1", "release_2026R4_SP1", 2, 4, 100);
    insertFormal("cc", "CC2027R1&R2", "release_2026R4_SP1", 5, 4, 100);
    insertFormal("cc", "CC2027R1&R2", "release_2026r4_sp1", 6, 99, 1);
    insertFormal("cc", "CC2027R1&R2", "release_2026R4_SP1_extra", 7, 99, 1);
    insertFormal("cc", "custom", "dev", 3, 8, 100);
    insertFormal("cc", "CC2027R10", "dev", 8, 99, 1);
    insertFormal("cc", "Other2027R1", "dev", 9, 99, 1);
    insertFormal("dgm", "CC2027R1&R2", "dev", 4, 9, 100);

    assertThat(support.defectDensity("cc", "CC2027R2", CodeReviewDataReadMode.FORMAL))
        .isEqualTo(30.0);
    assertThat(support.defectDensity("cc", "CC2026R4SP1", CodeReviewDataReadMode.FORMAL))
        .isEqualTo(40.0);
    assertThat(support.defectDensity("cc", "custom", CodeReviewDataReadMode.FORMAL))
        .isEqualTo(80.0);
    assertThat(support.defectDensity("cc", "CC2027R1", CodeReviewDataReadMode.FORMAL))
        .isEqualTo(30.0);
    assertThat(support.defectDensity("cc", "CC2099R9", CodeReviewDataReadMode.FORMAL))
        .isZero();
    assertThat(support.defectDensity("dgm", "CC2027R2", CodeReviewDataReadMode.FORMAL))
        .isEqualTo(90.0);
  }

  @Test
  void matchModeDeduplicatesRepeatedIidAndKeepsSpBranchExact() {
    insertMatch("cc", "crowncad", "CC2027R1&R2", "dev", 1, 101, 201, 2, 100);
    insertMatch("cc", "crowncad", "CC2027R1&R2", "dev", 1, 102, 201, 1, 100);
    insertMatch("cc", "crowncad", "CC2026R4SP1", "release_2026R4_SP1", 1, 103, 301, 4, 80);
    insertMatch("cc", "crowncad", "CC2027R1&R2", "release_2026R4_SP1", 1, 106, 303, 4, 80);
    insertMatch("cc", "crowncad", "CC2027R1&R2", "release_2026r4_sp1", 1, 107, 304, 99, 1);
    insertMatch("cc", "crowncad", "CC2027R1&R2", "release_2026R4_SP1_extra", 1, 108, 305, 99, 1);
    insertMatch("cc", "crowncad", "CC2026R4SP1", "release_2026R4_SP2", 1, 104, 302, 9, 80);
    insertMatch("dgm", "dgm", "CC2027R1&R2", "dev", 1, 105, 401, 7, 100);
    insertMatch("cc", "crowncad", "CC2027R10", "dev", 1, 109, 402, 99, 1);
    insertMatch("cc", "crowncad", "Other2027R1", "dev", 1, 110, 403, 99, 1);

    assertThat(support.defectDensity("cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE))
        .isEqualTo(30.0);
    assertThat(support.defectDensity("cc", "CC2026R4SP1", CodeReviewDataReadMode.MATCH_MODE))
        .isEqualTo(50.0);
    assertThat(support.defectDensity("cc", "CC2026R4SP2", CodeReviewDataReadMode.MATCH_MODE))
        .isEqualTo(112.5);
    assertThat(support.defectDensity("cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE))
        .isEqualTo(30.0);
    assertThat(support.defectDensity("cc", "", CodeReviewDataReadMode.MATCH_MODE))
        .isZero();
  }

  @Test
  void matchModeConsumersShareTheSameCombinationScope() {
    insertMatch("cc", "crowncad", "CC2027R1&R2", "dev", 1, 201, 501, 2, 100);

    assertThat(support.personDefectDensityRows(
        "author_name", false, "cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE))
        .extracting(QualityBoardChartRowResponse::name)
        .containsExactly("张三");
    assertThat(support.frequencyRows("cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE))
        .extracting(QualityBoardChartRowResponse::name)
        .containsExactly("张三");
    assertThat(support.codeReviewRecordRows("cc", "CC2027R2", CodeReviewDataReadMode.MATCH_MODE))
        .hasSize(1);
  }

  private void insertFormal(
      String source, String project, String branch, long mrId, int defects, int addedLines) {
    jdbcTemplate.update(
        """
        insert into merge_request_fact (
          source_system, source_instance, project_id, project_name, merge_request_id,
          merge_request_iid, merge_request_state, target_branch, defect_count, added_lines,
          deleted
        ) values ('LEGACY_PLATFORM', ?, 9, ?, ?, ?, 'MERGED', ?, ?, ?, false)
        """,
        source, project, mrId, mrId, branch, defects, addedLines);
  }

  private void insertMatch(
      String source, String repository, String project, String branch,
      long projectId, long mrId, long mrIid, int defects, int addedLines) {
    jdbcTemplate.update(
        """
        insert into code_review_match_mode_records (
          source_instance, project_id, repository_name, project_name, merge_request_id,
          merge_request_iid, merge_request_state, target_branch, author_name,
          reviewer_names, assignee_names, defect_count, added_lines
        ) values (?, ?, ?, ?, ?, ?, 'MERGED', ?, '张三', '王审查', '李四', ?, ?)
        """,
        source, projectId, repository, project, mrId, mrIid, branch, defects, addedLines);
  }
}
