package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormBindingRequest;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.entity.form.admin.FormScopeCreateRequest;
import com.data.collection.platform.service.form.FormPublicationService;
import com.data.collection.platform.service.form.FormSchemaStartupValidator;
import com.data.collection.platform.service.form.FormTemplateAdminService;
import com.data.collection.platform.service.form.FormVersionStructureVerifier;
import com.data.collection.platform.service.form.FormScopeValidationService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormSchemaMigrationTest {

  private static final String GITLAB_URL = "http://gitlab.test.local:18080";
  private static final FormActorContext ACTOR =
      new FormActorContext("employee-001", "员工一", Set.of());

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FormTemplateAdminService adminService;
  @Autowired private FormPublicationService publicationService;
  @Autowired private FormSchemaStartupValidator startupValidator;
  @Autowired private FormScopeValidationService scopeValidationService;
  @Autowired private FormTemplateSpecCatalog specCatalog;

  private final LegacyMigrationDriver migrationService = new LegacyMigrationDriver();

  @BeforeEach
  void resetFormState() {
    List<String> dynamicTables =
        jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = current_schema()", String.class);
    for (String table : dynamicTables) {
      if (table.startsWith("form_s")
          && (table.endsWith("_instances") || table.endsWith("_records"))) {
        jdbcTemplate.execute("drop table if exists " + table + " cascade");
      }
    }
    jdbcTemplate.execute("truncate table form_template_scopes restart identity cascade");
    jdbcTemplate.execute("truncate table collect_form_records restart identity cascade");
    jdbcTemplate.execute("drop sequence if exists form_record_id_probe_seq");
    jdbcTemplate.execute("select setval('form_record_id_seq', 1, false)");
    jdbcTemplate.update("delete from gitlab_sync_configs where name like 'form-w1-test%'");
  }

  @Test
  void shouldBootstrapEmptyPlatformWithoutScopes() {
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_definitions", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_scopes", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_permissions where permission_code like 'form.%'",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_role_permissions where permission_code = 'form.record.create'",
                Integer.class))
        .isPositive();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_default_role_permissions where permission_code = 'form.template.manage'",
                Integer.class))
        .isEqualTo(2);

    startupValidator.validate();
  }

  @Test
  void shouldAdvanceSequencePastOtherTemplateRecords() {
    long configId = insertSourceConfig();
    insertOtherTemplateRecordTable(102L);
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    FormInitialMigrationResult result =
        migrationService.migrateInitial(request(configId, List.of(9L), 9L, "CC2027R1"));

    assertThat(result.records()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(103L);
  }

  @Test
  void shouldNotLowerSharedSequenceWhenAlreadyAhead() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));
    jdbcTemplate.execute("select setval('form_record_id_seq', 5000, true)");

    migrationService.migrateInitial(request(configId, List.of(9L), 9L, "CC2027R1"));

    assertThat(jdbcTemplate.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(5001L);
  }

  @Test
  void shouldMigrateTwoRegisteredScopesSequentiallyWithoutReusingRecordIds() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        201, 172, 21L, "21", "Bob", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 2, 1, 9, 0), LocalDateTime.of(2026, 2, 1, 10, 0));
    FormInitialMigrationResult first =
        migrationService.migrateInitial(request(configId, List.of(172L), 172L, "CC2027R1"));

    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));
    FormInitialMigrationResult second =
        migrationService.migrateInitial(request(configId, List.of(9L), 9L, "CC2027R2"));

    assertThat(first.records()).isEqualTo(1);
    assertThat(second.records()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_scopes where status = 'ACTIVE'", Integer.class))
        .isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isGreaterThan(201L);
    String firstRecords = recordTable(first);
    String secondRecords = recordTable(second);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from (select id from "
                    + firstRecords
                    + " union all select id from "
                    + secondRecords
                    + ") all_ids",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(distinct id) from (select id from "
                    + firstRecords
                    + " union all select id from "
                    + secondRecords
                    + ") all_ids",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void shouldMigrateFirstScopeWhileSecondScopeIsFullyBoundDraft() {
    long configId = insertSourceConfig();
    long draftScopeId = insertBoundDraftScope("draft-172", configId, 172L);
    insertLegacyRow(
        201, 172, 21L, "21", "Bob", 7, 1, 1, 1, 1, 1, "note-201", false,
        LocalDateTime.of(2026, 2, 1, 9, 0), LocalDateTime.of(2026, 2, 1, 10, 0));
    insertLegacyRow(
        202, 9, 13L, "13", "Alice", 3, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    FormInitialMigrationResult first =
        migrationService.migrateInitial(request(configId, List.of(9L), 9L, "CC2027R1"));

    assertThat(first.records()).isEqualTo(1);
    assertThat(count("form_template_scopes")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_scopes where status = 'ACTIVE'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from form_template_scopes where id = ?",
                String.class,
                draftScopeId))
        .isEqualTo("DRAFT");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + recordTable(first) + " where id = 202",
                Integer.class))
        .isEqualTo(1);

    FormInitialMigrationResult second =
        migrationService.migrateInitial(request(configId, List.of(172L), 172L, "CC2027R2"));

    assertThat(second.scopeId()).isEqualTo(draftScopeId);
    assertThat(second.records()).isEqualTo(1);
    assertThat(count("form_template_scopes")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_scopes where status = 'ACTIVE'",
                Integer.class))
        .isEqualTo(2);
    String firstRecords = recordTable(first);
    String secondRecords = recordTable(second);
    Map<String, Object> record201 =
        jdbcTemplate.queryForMap("select * from " + secondRecords + " where id = 201");
    assertThat(record201.get("reviewer_name")).isEqualTo("Bob");
    assertThat(((Number) record201.get("review_duration_minutes")).intValue()).isEqualTo(7);
    assertThat(record201.get("supplement_note")).isEqualTo("note-201");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + firstRecords + " where id = 202",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + secondRecords + " where id = 202",
                Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(distinct id) from (select id from "
                    + firstRecords
                    + " union all select id from "
                    + secondRecords
                    + ") all_ids",
                Integer.class))
        .isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isGreaterThan(202L);
  }

  @Test
  void shouldNotCountBlankDraftScopeAsCoverage() {
    long configId = insertSourceConfig();
    insertBlankDraftScope("blank-172", 172L);
    insertLegacyRow(
        201, 172, 21L, "21", "Bob", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 2, 1, 9, 0), LocalDateTime.of(2026, 2, 1, 10, 0));
    insertLegacyRow(
        202, 9, 13L, "13", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configId, List.of(9L), 9L, "CC2027R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未被已登记范围覆盖");

    assertThat(count("form_template_scopes")).isEqualTo(2);
    assertThat(count("form_template_publications")).isZero();
  }

  @Test
  void shouldRejectVersionProjectOutsideBoundProjectsWithoutWriting() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configId, List.of(9L), 999L, "CC2027R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("发布项目必须属于范围项目集合");

    assertThat(count("form_template_versions")).isZero();
    assertThat(count("form_template_publications")).isZero();
  }

  @Test
  void shouldRejectSourceUrlChangeAtStartup() {
    long configId = insertSourceConfig();
    migrateSingleRow(configId, 301L, 9L);

    jdbcTemplate.update(
        "update gitlab_sync_configs set web_base_url = ? where id = ?",
        "http://gitlab.changed.local:18080",
        configId);

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("来源 Web 基址");
  }

  @Test
  void shouldRejectDeletedSourceConfigAtStartup() {
    long configId = insertSourceConfig();
    migrateSingleRow(configId, 301L, 9L);

    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", configId);

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("来源配置不存在");
  }

  @Test
  void shouldRejectOverlappingRegisteredProjectBeforeWriting() {
    long configId = insertSourceConfig();
    insertRegisteredScope("existing", configId, 9L);
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configId, List.of(9L), 9L, "CC2027R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTIVE 范围项目重叠");
    assertThat(count("form_template_publications")).isZero();
  }

  @Test
  void shouldRejectOverlappingActiveScopesAtStartup() {
    long configId = insertSourceConfig();
    insertRegisteredScope("existing-a", configId, 9L);
    insertRegisteredScope("existing-b", configId, 9L);

    assertThatThrownBy(scopeValidationService::validateActiveScopes)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTIVE 范围在项目上重叠");
  }

  @Test
  void shouldRejectDuplicateOwnershipAcrossRegisteredScopes() {
    long configA = insertSourceConfig();
    insertRegisteredScope("duplicate-a", configA, 9L);
    insertRegisteredScope("duplicate-b", configA, 9L);
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configA, List.of(10L), 10L, "CC2027R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACTIVE 范围在项目上重叠");
  }

  @Test
  void shouldRejectUncoveredLegacyRows() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));
    insertLegacyRow(
        102, 999, 13L, "13", "Bob", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configId, List.of(9L), 9L, "CC2027R1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未被已登记范围覆盖");
  }

  @Test
  void shouldIgnoreOtherTemplatesWhenSelectingLegacyS() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0));
    jdbcTemplate.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (301, ?, 9, null, 'issue', '30', 'issue_response', 'Other', 0, 0, 0, 0, 0, 0,
                null, false, current_timestamp, current_timestamp)
        """,
        GITLAB_URL);

    FormInitialMigrationResult result =
        migrationService.migrateInitial(request(configId, List.of(9L), 9L, "CC2027R1"));

    assertThat(result.records()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from collect_form_records", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + recordTable(result) + " where id = 101",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void shouldRejectMissingRequestKeyUniqueConstraintAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    jdbcTemplate.execute(
        "alter table "
            + recordTable
            + " drop constraint uq_fr_"
            + result.scopeId()
            + "_"
            + result.versionId()
            + "_request_key");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("约束集合不一致");
  }

  @Test
  void shouldRejectMissingInstanceNaturalKeyAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String instanceTable = instanceTable(result);
    jdbcTemplate.execute(
        "alter table "
            + instanceTable
            + " drop constraint uq_fi_"
            + result.scopeId()
            + "_"
            + result.versionId()
            + "_natural");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("约束集合不一致");
  }

  @Test
  void shouldRejectMissingForeignKeyAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    jdbcTemplate.execute(
        "alter table "
            + recordTable
            + " drop constraint fk_fr_"
            + result.scopeId()
            + "_"
            + result.versionId()
            + "_instance");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("约束集合不一致");
  }

  @Test
  void shouldRejectSameCountCheckReplacementAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    String checkName = "ck_fr_" + result.scopeId() + "_" + result.versionId() + "_1";
    jdbcTemplate.execute(
        "alter table " + recordTable + " drop constraint " + checkName);
    jdbcTemplate.execute(
        "alter table " + recordTable + " add constraint " + checkName + " check (true)");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("检查约束定义不一致");
  }

  @Test
  void shouldValidateGeneratedDdlThroughPinnedPoolAndCleanUpTempTables() throws Exception {
    long configId = insertSourceConfig();
    migrateSingleRow(configId, 301L, 9L);

    validateThroughPinnedPool();
  }

  @Test
  void shouldRejectCheckLogicalGroupingChangeAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    String checkName = "ck_fr_" + result.scopeId() + "_" + result.versionId() + "_2";
    jdbcTemplate.update(
        "update "
            + recordTable
            + " set created_by = 'tester', request_key = 'probe-key',"
            + " create_payload_hash = repeat('a', 64), template_version = 'CC2027R1'"
            + " where id = 301");
    jdbcTemplate.execute("alter table " + recordTable + " drop constraint " + checkName);
    jdbcTemplate.execute(
        "alter table "
            + recordTable
            + " add constraint "
            + checkName
            + " check ((created_by is null or request_key is not null)"
            + " and create_payload_hash is not null and template_version is not null)");

    assertThatThrownBy(this::validateThroughPinnedPool)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("检查约束定义不一致");
  }

  @Test
  void shouldRejectForeignKeyDeleteActionChangeAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    String instanceTable = instanceTable(result);
    String foreignKey = "fk_fr_" + result.scopeId() + "_" + result.versionId() + "_instance";
    jdbcTemplate.execute("alter table " + recordTable + " drop constraint " + foreignKey);
    jdbcTemplate.execute(
        "alter table "
            + recordTable
            + " add constraint "
            + foreignKey
            + " foreign key (instance_id) references "
            + instanceTable
            + " (id) on delete cascade");

    assertThatThrownBy(this::validateThroughPinnedPool)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("外键执行属性不一致");
  }

  @Test
  void shouldRejectRecordIdDefaultSequenceDriftAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    jdbcTemplate.execute("create sequence form_record_id_probe_seq");
    jdbcTemplate.execute(
        "alter table "
            + recordTable
            + " alter column id set default nextval('form_record_id_probe_seq')");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("记录ID默认序列不一致");
  }

  @Test
  void shouldRejectMissingInstanceIndexAtStartup() {
    long configId = insertSourceConfig();
    FormInitialMigrationResult result = migrateSingleRow(configId, 301L, 9L);
    String recordTable = recordTable(result);
    jdbcTemplate.execute(
        "drop index idx_fr_" + result.scopeId() + "_" + result.versionId() + "_instance");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少索引");
  }

  @Test
  void shouldMigrateLegacySelectionAndPreserveRecordIdentity() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        101, 9, 12L, "12", "Alice", 30, 1, 2, 3, 4, 5, "note-101", false,
        LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 1, 2, 11, 0));
    insertLegacyRow(
        102, 9, 17L, "17", null, 0, 0, 0, 0, 0, 0, null, true,
        LocalDateTime.of(2026, 1, 1, 12, 0), LocalDateTime.of(2026, 1, 3, 9, 0));
    insertLegacyRow(
        103, 9, 13L, "13", "", 5, 0, 0, 0, 0, 0, "", false,
        LocalDateTime.of(2026, 2, 1, 8, 0), LocalDateTime.of(2026, 2, 1, 8, 30));
    insertLegacyRow(
        104, 172, 14L, "14", "Bob", 10, 1, 1, 1, 1, 1, "note-104", false,
        LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 2, 9, 0));
    insertLegacyRow(
        105, 9, null, "15", "Cara", 20, 2, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 4, 1, 9, 0), LocalDateTime.of(2026, 4, 1, 10, 0));

    FormInitialMigrationResult result = migrationService.migrateInitial(request(configId));

    assertThat(result.sourceRows()).isEqualTo(5);
    assertThat(result.instances()).isEqualTo(5);
    assertThat(result.records()).isEqualTo(5);
    assertThat(result.ddlHash()).hasSize(64);

    Map<String, Object> scope =
        jdbcTemplate.queryForMap("select * from form_template_scopes where id = ?", result.scopeId());
    assertThat(scope.get("status")).isEqualTo("ACTIVE");
    assertThat(((Number) scope.get("current_version_id")).longValue()).isEqualTo(result.versionId());
    assertThat(((Number) scope.get("binding_revision")).longValue()).isEqualTo(1);
    assertThat(scope.get("write_fenced")).isEqualTo(false);

    Map<String, Object> version =
        jdbcTemplate.queryForMap("select * from form_template_versions where id = ?", result.versionId());
    assertThat(version.get("version_status")).isEqualTo("PUBLISHED");
    assertThat(version.get("version_label")).isEqualTo("CC2027R1");
    String recordTable = (String) version.get("record_table");

    Map<String, Object> record101 =
        jdbcTemplate.queryForMap("select * from " + recordTable + " where id = 101");
    assertThat(record101.get("reviewer_name")).isEqualTo("Alice");
    assertThat(((Number) record101.get("review_duration_minutes")).intValue()).isEqualTo(30);
    assertThat(((Number) record101.get("specification_count")).intValue()).isEqualTo(1);
    assertThat(((Number) record101.get("other_count")).intValue()).isEqualTo(5);
    assertThat(record101.get("supplement_note")).isEqualTo("note-101");
    assertThat(record101.get("deleted")).isEqualTo(false);
    assertThat(record101.get("legacy_incomplete")).isEqualTo(true);
    assertThat(legacyMissingFields(recordTable, 101L)).containsExactly("reviewer_user_id");
    assertThat(record101.get("origin_version_id"))
        .isEqualTo(
            jdbcTemplate.queryForObject(
                "select from_version_id from form_template_migration_logs where scope_id = ?",
                Long.class,
                result.scopeId()));

    Map<String, Object> record102 =
        jdbcTemplate.queryForMap("select * from " + recordTable + " where id = 102");
    assertThat(record102.get("reviewer_name")).isNull();
    assertThat(record102.get("deleted")).isEqualTo(true);
    assertThat(legacyMissingFields(recordTable, 102L))
        .containsExactlyInAnyOrder("reviewer_user_id", "reviewer_name");

    Map<String, Object> record103 =
        jdbcTemplate.queryForMap("select * from " + recordTable + " where id = 103");
    assertThat(record103.get("reviewer_name")).isEqualTo("");
    assertThat(legacyMissingFields(recordTable, 103L))
        .containsExactlyInAnyOrder("reviewer_user_id", "reviewer_name");

    Map<String, Object> instance =
        jdbcTemplate.queryForMap(
            "select * from " + version.get("instance_table") + " where project_id = 9 and resource_id = 12");
    assertThat(instance.get("created_at"))
        .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 10, 0)));
    assertThat(instance.get("updated_at"))
        .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 11, 0)));

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_migration_logs where scope_id = ?",
                Integer.class,
                result.scopeId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select migrated_rows from form_template_migration_logs where scope_id = ?",
                Long.class,
                result.scopeId()))
        .isEqualTo(5);
    assertThat(count("form_change_heads")).isEqualTo(5);
    assertThat(count("form_resource_authorities")).isEqualTo(5);
    assertThat(count("form_authority_cutover_items")).isEqualTo(5);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_change_heads where pending_storage_version_id = ? and status = 'PENDING'",
                Integer.class,
                result.versionId()))
        .isEqualTo(5);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_resource_authorities where authority = 'FORM' and authority_revision = 1",
                Integer.class))
        .isEqualTo(5);

    Map<String, Object> publication =
        jdbcTemplate.queryForMap(
            "select * from form_template_publications where id = ?", result.publicationId());
    assertThat(publication.get("stage")).isEqualTo("PUBLISHED");
    assertThat(publication.get("publication_kind")).isEqualTo("INITIAL_MIGRATION");
    assertThat(publication.get("attempt_no")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts where publication_id = ? and attempt_no = 1",
                String.class,
                result.publicationId()))
        .isEqualTo("PUBLISH");
    assertThat(
            jdbcTemplate.queryForObject(
                "select (verification_summary->>'fieldMismatches')::int from form_template_publication_attempts where publication_id = ? and attempt_no = 1",
                Integer.class,
                result.publicationId()))
        .isZero();

    Long nextId =
        jdbcTemplate.queryForObject("select nextval('form_record_id_seq')", Long.class);
    assertThat(nextId).isGreaterThan(105L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from collect_form_records", Integer.class))
        .isEqualTo(5);

    startupValidator.validate();
  }

  @Test
  void shouldRejectStructureDriftAtStartup() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        301, 9, 12L, "12", "Drift", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 6, 1, 9, 0), LocalDateTime.of(2026, 6, 1, 10, 0));
    FormInitialMigrationResult result = migrationService.migrateInitial(request(configId));

    String recordTable =
        jdbcTemplate.queryForObject(
            "select record_table from form_template_versions where id = ?",
            String.class,
            result.versionId());
    jdbcTemplate.execute("alter table " + recordTable + " add column drift_probe text");

    assertThatThrownBy(startupValidator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结构漂移");
  }

  @Test
  void shouldRollbackInitialMigrationWithoutSwitchingPointerWhenHeadOwnershipConflicts() {
    long configId = insertSourceConfig();
    insertLegacyRow(
        201, 325, 12L, "12", "Conflict", 1, 0, 0, 0, 0, 0, null, false,
        LocalDateTime.of(2026, 5, 1, 9, 0), LocalDateTime.of(2026, 5, 1, 10, 0));
    long conflictScopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(
                template_key, scope_key, resource_type, status, project_ids, binding_revision)
            values ('code_review', 'conflict', 'merge_request', 'DRAFT', '[325]'::jsonb, 0)
            returning id
            """,
            Long.class);
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            pending_storage_version_id, pending_authority_revision, status, fencing_version,
            created_at, updated_at)
        values (?, 'default', ?, 325, 'merge_request', 12, 'code_review', ?, 1, 0, null, null,
                'PENDING', 0, ?, ?)
        """,
        configId,
        GITLAB_URL,
        conflictScopeId,
        Timestamp.valueOf(LocalDateTime.of(2026, 5, 2, 9, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 5, 2, 9, 0)));

    assertThatThrownBy(
            () ->
                migrationService.migrateInitial(
                    request(configId, List.of(325L), 325L, "CC2027R2")))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_scopes where scope_key like 'legacy-%'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select stage from form_template_publications", String.class))
        .isEqualTo("FAILED");
    assertThat(count("form_change_heads")).isEqualTo(1);
    assertThat(count("form_resource_authorities")).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from pg_tables where schemaname = current_schema() and tablename like 'form\\_s%'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from collect_form_records", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select write_fenced from form_template_scopes where scope_key like 'legacy-%'",
                Boolean.class))
        .isFalse();
  }

  /**
   * 用真实多连接池执行一次启动校验。
   *
   * <p>每个 JdbcTemplate 调用借到独立物理连接，且代理连接不归还，迫使未固定会话的实现无法跨调用
   * 复用临时表；校验结束后核对所有会话都没有遗留 form_ck_probe_* 临时对象。
   */
  private void validateThroughPinnedPool() throws Exception {
    try (PinnedDataSource dataSource =
        new PinnedDataSource(
            POSTGRES.getJdbcUrl() + "?currentSchema=public",
            POSTGRES.getUsername(),
            POSTGRES.getPassword())) {
      assertThat(dataSource.maximumPoolSize()).isGreaterThan(1);
      JdbcTemplate pinned = new JdbcTemplate(dataSource);
      FormSchemaStartupValidator validator =
          new FormSchemaStartupValidator(
              pinned,
              new FormVersionSchemaService(objectMapper),
              new FormScopeValidationService(pinned, objectMapper),
              new FormVersionStructureVerifier(pinned),
              objectMapper);
      try {
        validator.run(new DefaultApplicationArguments(new String[0]));
      } finally {
        assertThat(dataSource.probeTableCount()).isZero();
      }
    }
  }

  private long insertSourceConfig() {
    return jdbcTemplate.queryForObject(
        """
        insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url)
        values ('form-w1-test', 'qaflex', 'qaflex', 'secret', ?)
        returning id
        """,
        Long.class,
        GITLAB_URL);
  }

  private FormInitialMigrationResult migrateSingleRow(
      long configId, long legacyId, long projectId) {
    insertLegacyRow(
        legacyId,
        projectId,
        legacyId,
        String.valueOf(legacyId),
        "Tester",
        1,
        0,
        0,
        0,
        0,
        0,
        null,
        false,
        LocalDateTime.of(2026, 1, 1, 9, 0),
        LocalDateTime.of(2026, 1, 1, 10, 0));
    return migrationService.migrateInitial(
        request(configId, List.of(projectId), projectId, "CC2027R1"));
  }

  private String recordTable(FormInitialMigrationResult result) {
    return jdbcTemplate.queryForObject(
        "select record_table from form_template_versions where id = ?",
        String.class,
        result.versionId());
  }

  private String instanceTable(FormInitialMigrationResult result) {
    return jdbcTemplate.queryForObject(
        "select instance_table from form_template_versions where id = ?",
        String.class,
        result.versionId());
  }

  private void insertOtherTemplateRecordTable(long recordId) {
    long scopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(
                template_key, scope_key, resource_type, status, project_ids, binding_revision)
            values ('issue_response', 'other-template', 'issue', 'DRAFT', '[9]'::jsonb, 0)
            returning id
            """,
            Long.class);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            scope_id, storage_kind, instance_table, record_table, field_spec, version_status)
        values (?, 'RECORDS', 'form_s90_v91_instances', 'form_s90_v91_records',
                '{"spec_version":1,"template_key":"issue_response","fields":[]}'::jsonb, 'RETIRED')
        """,
        scopeId);
    jdbcTemplate.execute("create table form_s90_v91_records (id bigint primary key)");
    jdbcTemplate.update("insert into form_s90_v91_records(id) values (?)", recordId);
  }

  private long insertRegisteredScope(String scopeKey, long configId, long projectId) {
    long scopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(
                template_key, scope_key, resource_type, status, project_ids, binding_revision)
            values ('code_review', ?, 'merge_request', 'DRAFT', ?::jsonb, 0)
            returning id
            """,
            Long.class,
            scopeKey,
            "[" + projectId + "]");
    String instanceTable = "form_s" + scopeId + "_v1_instances";
    String recordTable = "form_s" + scopeId + "_v1_records";
    long versionId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_versions(
                scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
                version_status, published_at)
            values (?, 'EXISTING', 'RECORDS', ?, ?,
                    '{"spec_version":1,"template_key":"code_review","fields":[]}'::jsonb,
                    'PUBLISHED', current_timestamp)
            returning id
            """,
            Long.class,
            scopeId,
            instanceTable,
            recordTable);
    jdbcTemplate.execute("create table " + instanceTable + " (id bigint primary key)");
    jdbcTemplate.execute("create table " + recordTable + " (id bigint primary key)");
    String bindingHash =
        FormScopeBinding.hash(
            objectMapper,
            FormScopeBinding.create(
                objectMapper,
                "code_review",
                configId,
                "default",
                GITLAB_URL,
                List.of(projectId),
                projectId,
                "merge_request"));
    jdbcTemplate.update(
        """
        update form_template_scopes
           set status = 'ACTIVE', current_version_id = ?, source_config_id = ?,
               source_instance = 'default', canonical_gitlab_base_url = ?,
               version_project_id = ?, binding_hash = ?
         where id = ?
        """,
        versionId,
        configId,
        GITLAB_URL,
        projectId,
        bindingHash,
        scopeId);
    return scopeId;
  }

  private long insertBoundDraftScope(String scopeKey, long configId, long projectId) {
    String bindingHash =
        FormScopeBinding.hash(
            objectMapper,
            FormScopeBinding.create(
                objectMapper,
                "code_review",
                configId,
                "default",
                GITLAB_URL,
                List.of(projectId),
                projectId,
                "merge_request"));
    return jdbcTemplate.queryForObject(
        """
        insert into form_template_scopes(
            template_key, scope_key, source_config_id, source_instance,
            canonical_gitlab_base_url, project_ids, version_project_id, resource_type,
            status, binding_revision, binding_hash)
        values ('code_review', ?, ?, 'default', ?, ?::jsonb, ?, 'merge_request', 'DRAFT', 1, ?)
        returning id
        """,
        Long.class,
        scopeKey,
        configId,
        GITLAB_URL,
        "[" + projectId + "]",
        projectId,
        bindingHash);
  }

  private void insertBlankDraftScope(String scopeKey, long projectId) {
    jdbcTemplate.update(
        """
        insert into form_template_scopes(
            template_key, scope_key, resource_type, status, project_ids, binding_revision)
        values ('code_review', ?, 'merge_request', 'DRAFT', ?::jsonb, 0)
        """,
        scopeKey,
        "[" + projectId + "]");
  }

  private void insertLegacyRow(
      long id,
      long projectId,
      Long requestIid,
      String resourceId,
      String reviewer,
      int duration,
      int specification,
      int logic,
      int performance,
      int design,
      int other,
      String remark,
      boolean deleted,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    jdbcTemplate.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        GITLAB_URL,
        projectId,
        requestIid,
        resourceId,
        reviewer,
        duration,
        specification,
        logic,
        performance,
        design,
        other,
        remark,
        deleted,
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(updatedAt));
  }

  /**
   * 以管理端冻结草稿 + 通用发布执行器驱动旧表首迁，替代已删除的一次性迁移入口。
   *
   * <p>测试仍按旧请求构造调用；驱动负责绑定复用、草稿写入、预览、发布与同步执行。
   */
  private final class LegacyMigrationDriver {

    FormInitialMigrationResult migrateInitial(FormInitialMigrationRequest request) {
      String canonicalUrl = FormSourceUrlSupport.canonicalize(request.gitlabBaseUrl());
      String bindingHash =
          FormScopeBinding.hash(
              objectMapper,
              FormScopeBinding.create(
                  objectMapper,
                  request.templateKey(),
                  request.sourceConfigId(),
                  "default",
                  canonicalUrl,
                  request.projectIds(),
                  request.versionProjectId(),
                  "merge_request"));
      List<Long> drafts =
          jdbcTemplate.queryForList(
              """
              select id from form_template_scopes
               where template_key = ? and resource_type = 'merge_request'
                 and status = 'DRAFT' and binding_hash = ?
               order by id
              """,
              Long.class,
              request.templateKey(),
              bindingHash);
      if (drafts.size() > 1) {
        throw new IllegalStateException("相同绑定的 DRAFT 范围不唯一: " + drafts);
      }
      long scopeId;
      if (drafts.size() == 1) {
        scopeId = drafts.getFirst();
      } else {
        scopeId =
            adminService
                .createScope(
                    new FormScopeCreateRequest(
                        request.templateKey(), "legacy-" + bindingHash.substring(0, 12)),
                    ACTOR)
                .scope()
                .id();
      }
      Integer draftRows =
          jdbcTemplate.queryForObject(
              "select count(*) from form_template_scope_drafts where scope_id = ?",
              Integer.class,
              scopeId);
      String seed = specCatalog.rawJson(request.templateKey());
      String evidence = json(request.versionEvidence());
      if (draftRows == null || draftRows == 0) {
        jdbcTemplate.update(
            """
            insert into form_template_scope_drafts(
                scope_id, draft_revision, field_spec, source_config_id, source_instance,
                canonical_gitlab_base_url, project_ids, version_project_id, version_label,
                version_evidence, binding_hash, migration_spec, updated_by, updated_at)
            values (?, 1, ?::jsonb, ?, 'default', ?, ?::jsonb, ?, ?, ?::jsonb, ?, null, ?, ?)
            """,
            scopeId,
            seed,
            request.sourceConfigId(),
            canonicalUrl,
            json(request.projectIds()),
            request.versionProjectId(),
            request.versionLabel(),
            evidence,
            bindingHash,
            request.operatorId(),
            Timestamp.valueOf(LocalDateTime.now()));
      } else {
        jdbcTemplate.update(
            """
            update form_template_scope_drafts
               set draft_revision = draft_revision + 1, field_spec = ?::jsonb,
                   source_config_id = ?, source_instance = 'default',
                   canonical_gitlab_base_url = ?, project_ids = ?::jsonb,
                   version_project_id = ?, version_label = ?, version_evidence = ?::jsonb,
                   binding_hash = ?, migration_spec = null, updated_by = ?, updated_at = ?
             where scope_id = ?
            """,
            seed,
            request.sourceConfigId(),
            canonicalUrl,
            json(request.projectIds()),
            request.versionProjectId(),
            request.versionLabel(),
            evidence,
            bindingHash,
            request.operatorId(),
            Timestamp.valueOf(LocalDateTime.now()),
            scopeId);
      }
      long revision =
          jdbcTemplate.queryForObject(
              "select draft_revision from form_template_scope_drafts where scope_id = ?",
              Long.class,
              scopeId);
      FormAdminViews.PreviewView preview = adminService.preview(scopeId, revision, ACTOR);
      if (!preview.blockingErrors().isEmpty()) {
        throw new IllegalStateException(String.join("；", preview.blockingErrors()));
      }
      FormAdminViews.PublicationView publication =
          adminService.createPublication(
              scopeId,
              new FormPublicationCreateRequest(
                  revision, preview.id(), null, UUID.randomUUID().toString()),
              ACTOR);
      if (!publicationService.executeOnce(publication.id())) {
        FormAdminViews.PublicationView failed =
            publicationService.publicationView(publication.id());
        throw new IllegalStateException("迁移发布失败：" + failed.lastError());
      }
      FormAdminViews.PublicationView finished =
          publicationService.publicationView(publication.id());
      String ddlHash =
          jdbcTemplate.queryForObject(
              "select ddl_hash from form_template_publications where id = ?",
              String.class,
              publication.id());
      Long migrationLogId =
          jdbcTemplate.queryForObject(
              "select id from form_template_migration_logs where scope_id = ? and to_version_id = ?",
              Long.class,
              scopeId,
              finished.toVersionId());
      JsonNode summary = finished.verificationSummary();
      return new FormInitialMigrationResult(
          scopeId,
          finished.toVersionId(),
          finished.id(),
          migrationLogId == null ? 0 : migrationLogId,
          ddlHash,
          summary == null ? 0 : summary.path("records").asInt(),
          summary == null ? 0 : summary.path("instances").asInt(),
          summary == null ? 0 : summary.path("records").asInt(),
          summary);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private FormInitialMigrationRequest request(long configId) {    return request(configId, List.of(172L, 9L, 9L), 9L, "CC2027R1");
  }

  private FormInitialMigrationRequest request(
      long configId, List<Long> projectIds, long versionProjectId, String versionLabel) {
    ObjectNode evidence = objectMapper.createObjectNode();
    evidence.put("sourceConfigId", configId);
    evidence.put("projectId", versionProjectId);
    evidence.put("milestoneId", 1);
    evidence.put("milestoneTitle", versionLabel);
    evidence.putObject("mapping").put(versionLabel, versionLabel);
    evidence.put("verifiedAt", "2026-10-22T00:00:00Z");
    return new FormInitialMigrationRequest(
        "code_review",
        configId,
        GITLAB_URL + "/",
        projectIds.stream().distinct().toList(),
        versionProjectId,
        versionLabel,
        evidence,
        "employee-001",
        "员工一",
        UUID.randomUUID().toString());
  }

  private List<String> legacyMissingFields(String recordTable, long id) {
    String raw =
        jdbcTemplate.queryForObject(
            "select legacy_missing_fields::text from " + recordTable + " where id = ?",
            String.class,
            id);
    if (raw == null || raw.equals("{}")) {
      return List.of();
    }
    return java.util.Arrays.stream(raw.substring(1, raw.length() - 1).split(","))
        .map(value -> value.replace("\"", ""))
        .toList();
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
  }

  private record FormInitialMigrationRequest(
      String templateKey,
      long sourceConfigId,
      String gitlabBaseUrl,
      List<Long> projectIds,
      long versionProjectId,
      String versionLabel,
      JsonNode versionEvidence,
      String operatorId,
      String operatorName,
      String requestKey) {}

  private record FormInitialMigrationResult(
      long scopeId,
      long versionId,
      long publicationId,
      long migrationLogId,
      String ddlHash,
      int sourceRows,
      int instances,
      int records,
      JsonNode summary) {}

  private static final class PinnedDataSource implements DataSource, AutoCloseable {

    private final HikariDataSource pool;
    private final Set<Connection> probeConnections =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private PinnedDataSource(String jdbcUrl, String username, String password) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(jdbcUrl);
      config.setUsername(username);
      config.setPassword(password);
      config.setMaximumPoolSize(64);
      config.setMinimumIdle(0);
      config.setConnectionTimeout(10000);
      this.pool = new HikariDataSource(config);
    }

    private int maximumPoolSize() {
      return pool.getMaximumPoolSize();
    }

    @Override
    public Connection getConnection() throws SQLException {
      Connection delegate = pool.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                  if (!probeConnections.contains(delegate)) {
                    delegate.close();
                  }
                  return null;
                }
                Object result = invoke(delegate, method, args);
                if ("createStatement".equals(method.getName())) {
                  return statementProxy(delegate, (Statement) result);
                }
                return result;
              });
    }

    private Statement statementProxy(Connection delegate, Statement statement) {
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {Statement.class},
              (proxy, method, args) -> {
                if (args != null
                    && args.length > 0
                    && args[0] instanceof String sql
                    && sql.startsWith("create temp table")) {
                  probeConnections.add(delegate);
                }
                return invoke(statement, method, args);
              });
    }

    private Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
        throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException error) {
        throw error.getCause();
      }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    private int probeTableCount() throws SQLException {
      int count = 0;
      for (Connection connection : probeConnections) {
        try (PreparedStatement statement =
                connection.prepareStatement(
                    "select count(*) from pg_class where relname like 'form_ck_probe_%'"
                        + " and relnamespace = pg_my_temp_schema()");
            ResultSet result = statement.executeQuery()) {
          if (result.next()) {
            count += result.getInt(1);
          }
        }
      }
      return count;
    }

    @Override
    public void close() {
      for (Connection connection : probeConnections) {
        try {
          connection.close();
        } catch (SQLException ignored) {
          // 连接池关闭时统一释放
        }
      }
      pool.close();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return pool.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      pool.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      pool.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return pool.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return pool.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return pool.isWrapperFor(iface);
    }
  }
}
