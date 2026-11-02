package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** FORM 权威 MR 的质量看板均时按逐记录 sum/count，MR 计数仍按去重合并请求。 */
@Testcontainers(disabledWithoutDocker = true)
class CodeReviewMultiBoardFormRecordsIntegrationTest {

  @Container
  private final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void formRecordsAverageDurationUsesPerRecordSumOverCount() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long revisionId =
        jdbc.queryForObject(
            """
            insert into merge_request_fact(
                source_system, source_instance, project_id, merge_request_id, merge_request_iid,
                title, merge_request_state, reviewer_names, review_status,
                review_duration_minutes, defect_count, added_lines, deleted,
                fact_refreshed_at, created_at, updated_at)
            values ('GITLAB', 'default', 9, 9001, 501, 'form-review', 'MERGED',
                    'Alice, Bob', 'COMPLETED', 30, 5, 100, false,
                    current_timestamp, current_timestamp, current_timestamp)
            returning id
            """,
            Long.class);
    insertReview(jdbc, revisionId, 9101L, "u-alice", "Alice", 10);
    insertReview(jdbc, revisionId, 9102L, "u-bob", "Bob", 20);

    QualityBoardCodeReviewReadSupport support = mock(QualityBoardCodeReviewReadSupport.class);
    QualityBoardCodeReviewReadScope scope =
        new QualityBoardCodeReviewReadScope(
            true,
            "code_review_formal_records",
            "default",
            List.of(),
            "project_id, merge_request_id",
            " and deleted = false",
            null,
            List.of());
    when(support.configuredReadMode()).thenReturn(CodeReviewDataReadMode.FORMAL);
    when(support.listAvailableSources(CodeReviewDataReadMode.FORMAL))
        .thenReturn(List.of(new OptionItemResponse("CC", "cc")));
    when(support.resolveAllProjectsScope("cc", CodeReviewDataReadMode.FORMAL)).thenReturn(scope);
    when(support.queryScope(scope))
        .thenReturn(
            new QualityBoardCodeReviewQueryScope("source_instance = ?", List.of("default")));

    CodeReviewMultiBoardOverviewResponse overview =
        new CodeReviewMultiBoardService(jdbc, support)
            .getOverview(new CodeReviewMultiBoardOverviewRequest("cc"));

    assertThat(overview.mergeRequestCount()).isEqualTo(1);
    assertThat(overview.averageReviewDurationMinutes()).isEqualTo(15.0);
    assertThat(overview.totalDefectCount()).isEqualTo(5);
    assertThat(overview.defectDensityPerKloc()).isEqualTo(50.0);
  }

  private static void insertReview(
      JdbcTemplate jdbc,
      long revisionId,
      long recordId,
      String reviewerUserId,
      String reviewerName,
      int duration) {
    jdbc.update(
        """
        insert into form_review_fact(
            merge_request_revision_id, source_instance, project_id, merge_request_id,
            merge_request_iid, record_id, scope_id, storage_version_id,
            reviewer_user_id, reviewer_name, review_duration_minutes,
            specification_count, logic_count, performance_count, design_count, other_count,
            record_created_at)
        values (?, 'default', 9, 9001, 501, ?, 1, 1, ?, ?, ?, 0, 0, 0, 0, 0,
                current_timestamp)
        """,
        revisionId,
        recordId,
        reviewerUserId,
        reviewerName,
        duration);
  }
}
