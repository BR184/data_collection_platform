package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.MergeRequestFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MR 逐记录走查投影的真实 PG 回归：两版各一记录聚合为 MR 事实，作废后只保留有效记录。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormReviewProjectionIntegrationTest {
  private static final String URL = "http://gitlab.projection.local";
  private static final long PROJECT_ID = 11L;
  private static final long ISSUE_ID = 9001L;
  private static final long ISSUE_IID = 501L;

  @Container
  private final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void twoVersionsAggregateIntoOneMrAndVoidedRecordLeavesOnlyValidValues() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(postgres);
    FormV6TestSupport.WiredServices services = FormV6TestSupport.wire(jdbc);
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId =
        FormV6TestSupport.insertScope(
            jdbc, "code_review", "p4-review-projection", configId, URL, PROJECT_ID, "DRAFT");
    FormTemplateSpecCatalog catalog = new FormTemplateSpecCatalog(new ObjectMapper());
    String spec = catalog.rawJson(FormTemplateSpecCatalog.CODE_REVIEW);
    FormV6TestSupport.FixtureTables v1 =
        FormV6TestSupport.createRecordVersion(
            jdbc, scopeId, "code_review", "V1", spec, "RETIRED", true);
    FormV6TestSupport.FixtureTables v2 =
        FormV6TestSupport.createRecordVersion(
            jdbc, scopeId, "code_review", "V2", spec, "PUBLISHED", true);
    long v1Id = versionId(jdbc, scopeId, v1.recordTable());
    long v2Id = versionId(jdbc, scopeId, v2.recordTable());
    long instanceV1 =
        FormV6TestSupport.insertInstance(
            jdbc, v1.instanceTable(), scopeId, configId, URL, PROJECT_ID, ISSUE_IID);
    long instanceV2 =
        FormV6TestSupport.insertInstance(
            jdbc, v2.instanceTable(), scopeId, configId, URL, PROJECT_ID, ISSUE_IID);
    insertRecord(
        jdbc, v1.recordTable(), instanceV1, v1Id, 9001L, "u-alice", "Alice", 10, 2,
        LocalDateTime.of(2026, 1, 1, 10, 0), false);
    insertRecord(
        jdbc, v2.recordTable(), instanceV2, v2Id, 9002L, "u-bob", "Bob", 20, 3,
        LocalDateTime.of(2026, 1, 2, 10, 0), false);
    insertLocation(jdbc, scopeId, v1Id, v1Id, "MATERIALIZED", instanceV1, configId, 9001L, v1Id);
    insertLocation(jdbc, scopeId, v2Id, v2Id, "MATERIALIZED", instanceV2, configId, 9002L, v2Id);
    FormV6TestSupport.insertFormAuthority(
        jdbc, scopeId, "code_review", "merge_request", PROJECT_ID, ISSUE_IID);

    MergeRequestFact formFact = fact(ISSUE_ID, ISSUE_IID, 100);
    MergeRequestFact commentFact = fact(9002L, 502L, 100);
    commentFact.setReviewDurationMinutes(15);
    commentFact.setCodeSpecificationCount(7);
    FormReviewProjectionService projectionService =
        new FormReviewProjectionService(jdbc, services.reader(), new ObjectMapper());

    FormReviewProjectionService.Projection projection =
        projectionService.load("default", List.of(formFact, commentFact));
    projectionService.apply(List.of(formFact, commentFact), projection);

    assertThat(projection.records()).as("两版各一记录").hasSize(2);
    assertThat(projection.records())
        .as("记录 2、MR 1")
        .allSatisfy(record -> assertThat(record.mergeRequestId()).isEqualTo(ISSUE_ID));
    assertThat(formFact.getReviewDurationMinutes()).isEqualTo(30);
    assertThat(formFact.getCodeSpecificationCount()).isEqualTo(5);
    assertThat(formFact.getReviewStatus()).isEqualTo("COMPLETED");
    assertThat(formFact.getReviewerNames()).isEqualTo("Alice, Bob");
    assertThat(formFact.getCodeWalkthroughDate())
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 10, 0));
    assertThat(formFact.getDefectCount()).isEqualTo(5);
    assertThat(formFact.getReviewDefectDensityPerKloc())
        .isEqualByComparingTo(new BigDecimal("50.00"));
    assertThat(formFact.getReviewDurationMinutes() / projection.records().size())
        .as("逐记录均时 15")
        .isEqualTo(15);
    assertThat(commentFact.getReviewDurationMinutes())
        .as("未登记 FORM 的 MR 保持原评论值")
        .isEqualTo(15);
    assertThat(commentFact.getCodeSpecificationCount()).isEqualTo(7);

    jdbc.update(
        "update " + v2.recordTable() + " set deleted = true where id = ?", 9002L);
    FormReviewProjectionService.Projection afterVoid =
        projectionService.load("default", List.of(formFact, commentFact));
    projectionService.apply(List.of(formFact), afterVoid);

    assertThat(afterVoid.records()).hasSize(1);
    assertThat(formFact.getReviewDurationMinutes()).isEqualTo(10);
    assertThat(formFact.getCodeSpecificationCount()).isEqualTo(2);
    assertThat(formFact.getDefectCount()).isEqualTo(2);
    assertThat(formFact.getReviewDefectDensityPerKloc())
        .isEqualByComparingTo(new BigDecimal("20.00"));
    assertThat(formFact.getReviewerNames()).isEqualTo("Alice");
    assertThat(formFact.getCodeWalkthroughDate())
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
  }

  private static MergeRequestFact fact(long mergeRequestId, long iid, int addedLines) {
    MergeRequestFact fact = new MergeRequestFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance("default");
    fact.setProjectId(PROJECT_ID);
    fact.setMergeRequestId(mergeRequestId);
    fact.setMergeRequestIid(iid);
    fact.setAddedLines(addedLines);
    return fact;
  }

  private static long versionId(JdbcTemplate jdbc, long scopeId, String recordTable) {
    return jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ? and record_table = ?",
        Long.class,
        scopeId,
        recordTable);
  }

  private static void insertRecord(
      JdbcTemplate jdbc,
      String recordTable,
      long instanceId,
      long versionId,
      long recordId,
      String reviewerUserId,
      String reviewerName,
      int duration,
      int specification,
      LocalDateTime createdAt,
      boolean deleted) {
    jdbc.update(
        "insert into "
            + recordTable
            + "(id, instance_id, origin_version_id, record_version, reviewer_user_id,"
            + " reviewer_name, review_duration_minutes, specification_count, logic_count,"
            + " performance_count, design_count, other_count, deleted, created_at, updated_at)"
            + " values (?, ?, ?, 1, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?)",
        recordId,
        instanceId,
        versionId,
        reviewerUserId,
        reviewerName,
        duration,
        specification,
        deleted,
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt));
  }

  private static void insertLocation(
      JdbcTemplate jdbc,
      long scopeId,
      long storageVersionId,
      long originVersionId,
      String instanceMode,
      long instanceId,
      long configId,
      long recordId,
      long specVersionId) {
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (?, ?, ?, ?, ?, ?, ?, 'default', ?, ?, 'merge_request', ?,
                null, null,
                (select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?),
                null, current_timestamp)
        """,
        recordId,
        scopeId,
        storageVersionId,
        originVersionId,
        instanceMode,
        instanceId,
        configId,
        URL,
        PROJECT_ID,
        String.valueOf(ISSUE_IID),
        specVersionId);
  }
}
