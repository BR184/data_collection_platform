package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CodeReviewMultiBoardBreakdownRowResponse;
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

/** FORM/COMMENT 持久权威分区的走查均时：FORM 按逐记录，COMMENT 按每合并请求，不跨口径混算。 */
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
    JdbcTemplate jdbc = migrated();
    long revisionId =
        insertFact(jdbc, 9001L, 501L, "Alice, Bob", 30, 5, 100, "mod");
    insertReview(jdbc, revisionId, 9101L, "u-alice", "Alice", 10);
    insertReview(jdbc, revisionId, 9102L, "u-bob", "Bob", 20);

    CodeReviewMultiBoardOverviewResponse overview =
        service(jdbc).getOverview(new CodeReviewMultiBoardOverviewRequest("cc"));

    assertThat(overview.mergeRequestCount()).isEqualTo(1);
    assertThat(overview.averageReviewDurationMinutes()).isEqualTo(15.0);
    assertThat(overview.totalDefectCount()).isEqualTo(5);
    assertThat(overview.defectDensityPerKloc()).isEqualTo(50.0);
    assertThat(overview.reviewDurationPartitions()).hasSize(1);
    assertThat(overview.reviewDurationPartitions().getFirst().authority()).isEqualTo("FORM");
    assertThat(overview.reviewDurationPartitions().getFirst().recordCount()).isEqualTo(2);
  }

  @Test
  void mixedFormAndCommentKeepPartitionsAndLeaveOverallDurationEmpty() {
    JdbcTemplate jdbc = migrated();
    insertFact(jdbc, 9002L, 502L, "Alice, Bob", 100, 0, 50, "mod");

    CodeReviewMultiBoardOverviewResponse commentOnly =
        service(jdbc).getOverview(new CodeReviewMultiBoardOverviewRequest("cc"));
    assertThat(commentOnly.mergeRequestCount()).isEqualTo(1);
    assertThat(commentOnly.averageReviewDurationMinutes()).as("全 COMMENT 输出保持").isEqualTo(100.0);
    assertThat(commentOnly.reviewDurationPartitions()).hasSize(1);
    assertThat(commentOnly.reviewDurationPartitions().getFirst().authority()).isEqualTo("COMMENT");
    assertThat(commentOnly.reviewDurationPartitions().getFirst().recordCount()).isEqualTo(1);

    long revisionId =
        insertFact(jdbc, 9001L, 501L, "Alice, Bob", 30, 5, 100, "mod");
    insertReview(jdbc, revisionId, 9101L, "u-alice", "Alice, Bob", 10);
    insertReview(jdbc, revisionId, 9102L, "u-bob", "Alice, Bob", 20);

    CodeReviewMultiBoardOverviewResponse overview =
        service(jdbc).getOverview(new CodeReviewMultiBoardOverviewRequest("cc"));

    assertThat(overview.mergeRequestCount()).isEqualTo(2);
    assertThat(overview.averageReviewDurationMinutes()).as("混合口径整体均时显式为空").isNull();
    assertThat(overview.reviewDurationPartitions()).hasSize(2);
    assertPartition(overview.reviewDurationPartitions().get(0), "FORM", 1, 2, 15.0);
    assertPartition(overview.reviewDurationPartitions().get(1), "COMMENT", 1, 1, 100.0);

    CodeReviewMultiBoardBreakdownRowResponse owner =
        overview.ownerRows().stream()
            .filter(row -> "Alice, Bob".equals(row.rowLabel()))
            .findFirst()
            .orElseThrow();
    assertThat(owner.mergeRequestCount()).isEqualTo(2);
    assertThat(owner.averageReviewDurationMinutes()).as("分桶同样不混算").isNull();
    assertThat(owner.reviewDurationPartitions()).hasSize(2);
    assertPartition(owner.reviewDurationPartitions().get(0), "FORM", 1, 2, 15.0);
    assertPartition(owner.reviewDurationPartitions().get(1), "COMMENT", 1, 1, 100.0);

    CodeReviewMultiBoardBreakdownRowResponse module =
        overview.moduleRows().stream()
            .filter(row -> "mod".equals(row.rowLabel()))
            .findFirst()
            .orElseThrow();
    assertThat(module.reviewDurationPartitions()).hasSize(2);
    assertPartition(module.reviewDurationPartitions().get(0), "FORM", 1, 2, 15.0);
    assertPartition(module.reviewDurationPartitions().get(1), "COMMENT", 1, 1, 100.0);
  }

  private static void assertPartition(
      com.data.collection.platform.entity.CodeReviewDurationPartitionResponse partition,
      String authority,
      int mergeRequestCount,
      int recordCount,
      double average) {
    assertThat(partition.authority()).isEqualTo(authority);
    assertThat(partition.mergeRequestCount()).isEqualTo(mergeRequestCount);
    assertThat(partition.recordCount()).isEqualTo(recordCount);
    assertThat(partition.averageReviewDurationMinutes()).isEqualTo(average);
  }

  private JdbcTemplate migrated() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    return new JdbcTemplate(dataSource);
  }

  private static long insertFact(
      JdbcTemplate jdbc,
      long mergeRequestId,
      long iid,
      String reviewerNames,
      int duration,
      int defectCount,
      int addedLines,
      String moduleName) {
    return jdbc.queryForObject(
        """
        insert into merge_request_fact(
            source_system, source_instance, project_id, merge_request_id, merge_request_iid,
            title, module_name, merge_request_state, reviewer_names, review_status,
            review_duration_minutes, defect_count, added_lines, deleted,
            fact_refreshed_at, created_at, updated_at)
        values ('GITLAB', 'default', 9, ?, ?, 'form-review', ?, 'MERGED',
                ?, 'COMPLETED', ?, ?, ?, false,
                current_timestamp, current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        mergeRequestId,
        iid,
        moduleName,
        reviewerNames,
        duration,
        defectCount,
        addedLines);
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

  private static CodeReviewMultiBoardService service(JdbcTemplate jdbc) {
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
    return new CodeReviewMultiBoardService(jdbc, support);
  }
}
