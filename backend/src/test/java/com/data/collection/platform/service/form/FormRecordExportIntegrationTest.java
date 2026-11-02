package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.exception.FormPayloadTooLargeException;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** P5：共同字段真实目录聚合与按版导出的版本/项目/目录 C 谓词、审计和有界输出。 */
@Testcontainers(disabledWithoutDocker = true)
class FormRecordExportIntegrationTest {
  private static final String URL = "http://gitlab.p5.local";
  private static final long PROJECT_A = 11L;
  private static final long PROJECT_B = 12L;
  private static final AtomicInteger SCOPE_SEQUENCE = new AtomicInteger();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final FormTemplateSpecCatalog CATALOG = new FormTemplateSpecCatalog(MAPPER);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  private static JdbcTemplate jdbc;
  private static FormV6TestSupport.WiredServices services;
  private static FormCommonFieldService commonFieldService;
  private static FormRecordExportService exportService;

  @BeforeAll
  static void migrate() {
    jdbc = FormV6TestSupport.migrate(POSTGRES);
    services = FormV6TestSupport.wire(jdbc);
    commonFieldService =
        new FormCommonFieldService(
            jdbc,
            services.contextService(),
            services.reader(),
            services.comparableService(),
            services.objectMapper());
    exportService =
        new FormRecordExportService(
            jdbc, services.reader(), services.contextService(), services.objectMapper());
    jdbc.update(
        "insert into platform_ldap_users(user_id, real_name) values ('u-alice', 'Alice')"
            + " on conflict (user_id) do update set real_name = excluded.real_name");
    jdbc.update(
        "insert into platform_ldap_users(user_id, real_name) values ('u-bob', 'Bob')"
            + " on conflict (user_id) do update set real_name = excluded.real_name");
  }

  @Test
  void twoVersionsAggregateCommonMetricAndVoidUpdatesIt() {
    Fixture fixture = fixture("common", PROJECT_A);
    Fixture.Tables v1 = fixture.version("V1", PROJECT_A, 2, "u-alice", 10);
    Fixture.Tables v2 = fixture.version("V2", PROJECT_B, 3, "u-bob", 20);

    FormCommonFieldService.MetricResult metric =
        commonFieldService.aggregateForVersions(
            List.of(v1.versionId(), v2.versionId()), "code_review.specification_count");

    assertThat(metric.status()).isEqualTo("OK");
    assertThat(metric.total()).isEqualTo(5L);
    assertThat(metric.contributions())
        .extracting(FormCommonFieldService.Contribution::total)
        .containsExactly(2L, 3L);
    assertThat(metric.contributingVersions()).containsExactly(v1.versionId(), v2.versionId());

    jdbc.update("update " + v2.recordTable() + " set deleted = true where id = ?", v2.recordId());
    FormCommonFieldService.MetricResult afterVoid =
        commonFieldService.aggregateForVersions(
            List.of(v1.versionId(), v2.versionId()), "code_review.specification_count");
    assertThat(afterVoid.total()).isEqualTo(2L);
    assertThat(afterVoid.deleted()).isEqualTo(1);
    assertThat(afterVoid.contributingVersions()).containsExactly(v1.versionId());
  }

  @Test
  void exportHonorsVersionProjectAndVoidPredicatesAndWritesAudit() throws Exception {
    Fixture fixture = fixture("export", PROJECT_A, PROJECT_A, PROJECT_B);
    Fixture.Tables v1 = fixture.version("V1", PROJECT_A, 2, "u-alice", 10);
    Fixture.Tables duplicate = fixture.version("V1-dup", PROJECT_B, 3, "u-bob", 20);

    FormRecordExportService.ExportResult all =
        exportService.export(v1.versionId(), List.of(), "tester");
    assertThat(all.rowCount()).isEqualTo(1);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(all.content()))) {
      var sheet = workbook.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("记录ID");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
          .isEqualTo(Long.toString(v1.recordId()));
      assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("Alice");
    }
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_export_logs where version_id = ?",
        Long.class,
        v1.versionId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select row_count from form_record_export_logs where version_id = ?",
        Long.class,
        v1.versionId())).isEqualTo(1L);

    FormRecordExportService.ExportResult projectOnly =
        exportService.export(v1.versionId(), List.of(PROJECT_A), "tester");
    assertThat(projectOnly.rowCount()).isEqualTo(1);

    jdbc.update("update " + v1.recordTable() + " set deleted = true where id = ?", v1.recordId());
    FormRecordExportService.ExportResult afterVoid =
        exportService.export(v1.versionId(), List.of(), "tester");
    assertThat(afterVoid.rowCount()).isZero();
    assertThat(duplicate.versionId()).isNotEqualTo(v1.versionId());
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(afterVoid.content()))) {
      assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
    }
  }

  @Test
  void exportRejectsForeignProjectAndUnreadableVersion() {
    Fixture fixture = fixture("guard", PROJECT_A);
    Fixture.Tables v1 = fixture.version("V1", PROJECT_A, 2, "u-alice", 10);

    assertThatThrownBy(() -> exportService.export(v1.versionId(), List.of(PROJECT_B), "tester"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("必须属于该版本所在范围");

    jdbc.update(
        "update form_template_versions set record_source_role = 'TARGET' where id = ?",
        v1.versionId());
    assertThatThrownBy(() -> exportService.export(v1.versionId(), List.of(), "tester"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("已发布或已退役");
  }

  @Test
  void exportLimitsAreExplicitAndEmptyVersionStillHasHeader() throws Exception {
    Fixture fixture = fixture("limits", PROJECT_A);
    Fixture.Tables v1 = fixture.version("V1", PROJECT_A, 2, "u-alice", 10);
    Fixture.Tables empty = fixture.emptyVersion("EMPTY");

    FormRecordExportService rowLimited =
        new FormRecordExportService(
            jdbc, services.reader(), services.contextService(), services.objectMapper(), 0, 1024);
    assertThatThrownBy(() -> rowLimited.export(v1.versionId(), List.of(), "tester"))
        .isInstanceOf(FormPayloadTooLargeException.class)
        .hasMessageContaining("行数超过上限");

    FormRecordExportService byteLimited =
        new FormRecordExportService(
            jdbc, services.reader(), services.contextService(), services.objectMapper(), 10, 1);
    assertThatThrownBy(() -> byteLimited.export(v1.versionId(), List.of(), "tester"))
        .isInstanceOf(FormPayloadTooLargeException.class)
        .hasMessageContaining("文件超过上限");

    FormRecordExportService.ExportResult result =
        exportService.export(empty.versionId(), List.of(), "tester");
    assertThat(result.rowCount()).isZero();
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.content()))) {
      Row header = workbook.getSheetAt(0).getRow(0);
      assertThat((int) header.getLastCellNum()).isGreaterThan(0);
      assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
    }
  }

  private Fixture fixture(String key, Long... projects) {
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId =
        FormV6TestSupport.insertScope(
            jdbc,
            "code_review",
            "p5-" + key + "-" + SCOPE_SEQUENCE.incrementAndGet(),
            configId,
            URL,
            List.of(projects),
            projects[0],
            "DRAFT");
    FormV6TestSupport.insertFormAuthority(
        jdbc, scopeId, "code_review", "merge_request", projects[0], 501);
    return new Fixture(configId, scopeId);
  }

  private record Fixture(long configId, long scopeId) {
    private Tables version(
        String label, long projectId, int specification, String createdBy, int duration) {
      FormV6TestSupport.FixtureTables tables =
          FormV6TestSupport.createRecordVersion(
              jdbc,
              scopeId,
              "code_review",
              label,
              CATALOG.rawJson(FormTemplateSpecCatalog.CODE_REVIEW),
              "PUBLISHED",
              true);
      FormTemplateSpec spec =
          FormV6TestSupport.parseSpec(
              CATALOG.rawJson(FormTemplateSpecCatalog.CODE_REVIEW), "code_review");
      long instanceId =
          FormV6TestSupport.insertInstance(
              jdbc, tables.instanceTable(), scopeId, configId, URL, projectId, 501);
      long recordId = 800_000 + SCOPE_SEQUENCE.incrementAndGet();
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("reviewer_user_id", createdBy);
      values.put("reviewer_name", "Alice".equals(createdBy) ? "Alice" : "Bob");
      values.put("review_duration_minutes", duration);
      values.put("specification_count", specification);
      values.put("logic_count", 0);
      values.put("performance_count", 0);
      values.put("design_count", 0);
      values.put("other_count", 0);
      insertRecordRow(
          jdbc, tables.recordTable(), recordId, instanceId, tables.versionId(), createdBy, values);
      insertLocation(
          jdbc, recordId, scopeId, tables, instanceId, configId, "501", projectId, createdBy);
      registerComparable(scopeId, tables.versionId(), spec);
      return new Tables(
          tables.versionId(), tables.recordTable(), recordId, spec, tables);
    }

    private Tables emptyVersion(String label) {
      FormV6TestSupport.FixtureTables tables =
          FormV6TestSupport.createRecordVersion(
              jdbc,
              scopeId,
              "code_review",
              label,
              CATALOG.rawJson(FormTemplateSpecCatalog.CODE_REVIEW),
              "PUBLISHED",
              true);
      FormTemplateSpec spec =
          FormV6TestSupport.parseSpec(
              CATALOG.rawJson(FormTemplateSpecCatalog.CODE_REVIEW), "code_review");
      registerComparable(scopeId, tables.versionId(), spec);
      return new Tables(tables.versionId(), tables.recordTable(), 0L, spec, tables);
    }

    private record Tables(
        long versionId,
        String recordTable,
        long recordId,
        FormTemplateSpec spec,
        FormV6TestSupport.FixtureTables fixture) {}
  }

  private static void registerComparable(
      long scopeId, long versionId, FormTemplateSpec spec) {
    FormComparableRegistrationService.FieldDeclaration specification =
        new FormComparableRegistrationService.FieldDeclaration(
            "specification_count",
            "code_review.specification_count",
            "code_review.specification_count",
            "integer",
            "count",
            "规范问题数");
    FormComparableRegistrationService.FieldDeclaration duration =
        new FormComparableRegistrationService.FieldDeclaration(
            "review_duration_minutes",
            "code_review.review_duration_minutes",
            "code_review.review_duration_minutes",
            "integer",
            "minute",
            "走查时长");
    services.tx().execute(
        status -> services.comparableService().register(
            scopeId, versionId, List.of(specification, duration), "tester"));
  }

  private static void insertRecordRow(
      JdbcTemplate jdbc,
      String recordTable,
      long recordId,
      long instanceId,
      long versionId,
      String createdBy,
      Map<String, Object> values) {
    List<String> columns =
        new java.util.ArrayList<>(
            List.of(
                "id",
                "instance_id",
                "origin_version_id",
                "record_version",
                "deleted",
                "created_by",
                "template_version",
                "request_key",
                "create_payload_hash",
                "created_at",
                "updated_at"));
    List<Object> args =
        new java.util.ArrayList<>(
            List.of(
                recordId,
                instanceId,
                versionId,
                1,
                false,
                createdBy,
                "v-test",
                "req-" + recordId,
                "b".repeat(64),
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 3, 1, 8, 0)),
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 3, 1, 8, 0))));
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      columns.add(entry.getKey());
      args.add(entry.getValue());
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
    jdbc.update(
        "insert into " + recordTable
            + " (" + String.join(", ", columns) + ") values (" + placeholders + ")",
        args.toArray());
  }

  private static void insertLocation(
      JdbcTemplate jdbc,
      long recordId,
      long scopeId,
      FormV6TestSupport.FixtureTables version,
      long instanceId,
      long configId,
      String resourceId,
      long projectId,
      String createdBy) {
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, 'default', ?, ?, 'merge_request', ?,
                null, ?,
                (select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?),
                null, current_timestamp)
        """,
        recordId, scopeId, version.versionId(), version.versionId(), instanceId, configId, URL,
        projectId, resourceId, createdBy, version.versionId());
  }
}
