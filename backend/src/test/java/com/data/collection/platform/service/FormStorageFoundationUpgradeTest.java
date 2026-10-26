package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.form.FormSchemaStartupValidator;
import com.data.collection.platform.service.form.FormVersionStructureVerifier;
import com.data.collection.platform.service.form.FormScopeValidationService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 代表性非空旧库升级：先停在上一个迁移版本并写入旧走查数据，再升级到 W1 基础表。 */
@Testcontainers(disabledWithoutDocker = true)
class FormStorageFoundationUpgradeTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void shouldUpgradeNonEmptyLegacyDatabaseWithoutChangingExistingRows() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("20261015.03")
        .load()
        .migrate();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    assertThat(
            jdbcTemplate.queryForObject(
                "select to_regclass('form_template_definitions')", String.class))
        .isNull();
    long configId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url)
            values ('upgrade-test', 'qaflex', 'qaflex', 'secret', 'http://gitlab.legacy.local')
            returning id
            """,
            Long.class);
    jdbcTemplate.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values
          (11, 'http://gitlab.legacy.local', 9, 101, 'merge_request', '101', 'code_review',
           'Alice', 30, 1, 2, 3, 4, 5, 'legacy-a', false, ?, ?),
          (12, 'http://gitlab.legacy.local', 9, 102, 'merge_request', '102', 'code_review',
           null, 0, 0, 0, 0, 0, 0, null, true, ?, ?),
          (13, 'http://gitlab.legacy.local', 172, 103, 'merge_request', '103', 'code_review',
           '', 5, 0, 0, 0, 0, 0, '', false, ?, ?)
        """,
        Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 10, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 10, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 10, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 2, 2, 10, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 10, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 3, 2, 10, 0)));

    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    for (String table :
        List.of(
            "form_template_definitions",
            "form_template_scopes",
            "form_template_versions",
            "form_template_scope_drafts",
            "form_template_preview_evidence",
            "form_record_audit_logs",
            "form_template_migration_logs",
            "form_template_publications",
            "form_template_publication_attempts",
            "form_authority_cutover_manifests",
            "form_authority_cutover_items",
            "form_resource_authorities",
            "form_change_heads")) {
      assertThat(jdbcTemplate.queryForObject("select to_regclass(?)", String.class, table))
          .as(table)
          .isNotNull();
    }
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
                "select count(*) from collect_form_records", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "select reviewer from collect_form_records where id = 11", String.class))
        .isEqualTo("Alice");
    assertThat(
            jdbcTemplate.queryForObject(
                "select deleted from collect_form_records where id = 12", Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_permissions where permission_code like 'form.%'",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_default_role_permissions "
                    + "where permission_code in ('form.record.create', 'form.record.edit_own', "
                    + "'form.record.delete_own')",
                Integer.class))
        .isEqualTo(15);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from platform_default_role_permissions "
                    + "where permission_code = 'form.template.manage'",
                Integer.class))
        .isEqualTo(2);
    assertThat(configId).isPositive();

    ObjectMapper objectMapper = new ObjectMapper();
    FormSchemaStartupValidator validator =
        new FormSchemaStartupValidator(
            jdbcTemplate,
            new FormVersionSchemaService(objectMapper),
            new FormScopeValidationService(jdbcTemplate, objectMapper),
            new FormVersionStructureVerifier(jdbcTemplate),
            objectMapper);
    validator.validate();
  }
}
