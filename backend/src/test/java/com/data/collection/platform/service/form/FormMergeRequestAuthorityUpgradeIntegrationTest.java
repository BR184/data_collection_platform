package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * N4 R04 历史权威回填：169 条迁移的旧库升级时，已发布候选内“有 CONSUMED FORM 捕获证据但无走查记录”的
 * MR 必须保留 FORM，同批 COMMENT MR 不变；修正只改权威标记，事实、投影记录与捕获证据不被改写。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormMergeRequestAuthorityUpgradeIntegrationTest {

  private static final String URL = "http://gitlab.authority-upgrade.local";
  private static final String INSTANCE = "authority-upgrade-src";
  private static final long PROJECT_ID = 41L;
  private static final long SOURCE_CONFIG_ID = 7L;
  private static final long EMPTY_FORM_IID = 101L;
  private static final long COMMENT_IID = 102L;
  private static final long RECORDED_FORM_IID = 103L;
  private static final String V169 = "20261102.03";
  private static final String V170 = "20261102.04";
  private static final String V171 = "20261102.05";

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void upgradeKeepsFormAuthorityFromConsumedCaptureEvidenceAndLeavesCommentUnchanged() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertThat(migrate(dataSource, V169).migrationsExecuted).isEqualTo(169);
    long captureHeadId = seedPublishedCandidate(jdbc);
    List<Map<String, String>> factsBefore = snapshot(jdbc, "merge_request_fact");
    List<Map<String, String>> recordsBefore = snapshot(jdbc, "form_review_fact");
    List<Map<String, String>> capturesBefore = snapshot(jdbc, "form_publication_captures");

    assertThat(migrate(dataSource, V170).migrationsExecuted).isEqualTo(1);
    assertThat(authority(jdbc, EMPTY_FORM_IID))
        .as("170 只按投影行回填，空记录 FORM 事实会先被错误标为 COMMENT")
        .isEqualTo("COMMENT");
    assertThat(authority(jdbc, COMMENT_IID)).isEqualTo("COMMENT");
    assertThat(authority(jdbc, RECORDED_FORM_IID)).isEqualTo("FORM");

    assertThat(migrate(dataSource, V171).migrationsExecuted).isEqualTo(1);
    assertThat(authority(jdbc, EMPTY_FORM_IID))
        .as("按所属发布的 CONSUMED FORM 捕获证据恢复 FORM")
        .isEqualTo("FORM");
    assertThat(authority(jdbc, COMMENT_IID)).as("同批 COMMENT 无 FORM 证据保持不变").isEqualTo("COMMENT");
    assertThat(authority(jdbc, RECORDED_FORM_IID)).as("已有真实 FORM 投影保持 FORM").isEqualTo("FORM");

    assertThat(snapshot(jdbc, "merge_request_fact"))
        .as("原事实不被改写")
        .isEqualTo(factsBefore);
    assertThat(snapshot(jdbc, "form_review_fact"))
        .as("原投影记录不被改写")
        .isEqualTo(recordsBefore);
    assertThat(snapshot(jdbc, "form_publication_captures"))
        .as("原捕获证据不被改写")
        .isEqualTo(capturesBefore);
    assertThat(captureHeadId).isPositive();
  }

  private MigrateResult migrate(DriverManagerDataSource dataSource, String target) {
    org.flywaydb.core.api.configuration.FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateOnMigrate(true);
    if (target != null) {
      configuration = configuration.target(target);
    }
    return configuration.load().migrate();
  }

  private static long seedPublishedCandidate(JdbcTemplate jdbc) {
    UUID domainId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID publicationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID epochId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    jdbc.update(
        "insert into read_publication_domains(id, producer_kind, source_instance, fact_type,"
            + " table_key, next_sequence) values (?, 'FACT_REFRESH', ?, 'MERGE_REQUEST',"
            + " 'merge_request_fact', 2)",
        domainId,
        INSTANCE);
    jdbc.update(
        "insert into read_publications(id, domain_id, epoch_id, sequence_no, source_seal,"
            + " covered_change_version, state, data_as_of, published_at)"
            + " values (?, ?, ?, 1, '{}'::jsonb, 1, 'PUBLISHED', current_timestamp,"
            + " current_timestamp)",
        publicationId,
        domainId,
        epochId);

    long scopeId =
        jdbc.queryForObject(
            "insert into form_template_scopes(template_key, scope_key, source_config_id,"
                + " source_instance, canonical_gitlab_base_url, project_ids, version_project_id,"
                + " resource_type, status, updated_at)"
                + " values ('code_review', 'authority-upgrade', ?, ?, ?, '[41]'::jsonb, 41,"
                + " 'merge_request', 'DRAFT', current_timestamp) returning id",
            Long.class,
            SOURCE_CONFIG_ID,
            INSTANCE,
            URL);
    long versionId =
        jdbc.queryForObject(
            "insert into form_template_versions(scope_id, version_label, storage_kind,"
                + " instance_table, record_table, field_spec, version_status, published_at)"
                + " values (?, 'V1', 'RECORDS', 'form_inst_authority_upgrade',"
                + " 'form_rec_authority_upgrade', '{\"fields\":[]}'::jsonb, 'PUBLISHED',"
                + " current_timestamp) returning id",
            Long.class,
            scopeId);
    insertAuthority(jdbc, scopeId, EMPTY_FORM_IID, "FORM");
    insertAuthority(jdbc, scopeId, COMMENT_IID, "COMMENT");
    insertAuthority(jdbc, scopeId, RECORDED_FORM_IID, "FORM");
    long captureHeadId =
        insertHead(jdbc, scopeId, EMPTY_FORM_IID);
    insertHead(jdbc, scopeId, RECORDED_FORM_IID);
    jdbc.update(
        "insert into form_publication_captures(publication_id, head_id, scope_id, capture_token,"
            + " source_set_revision, source_set_hash, latest_change_version, authority_revision,"
            + " lease_owner, fencing_version, status, captured_at, consumed_at)"
            + " values (?, ?, ?, ?::uuid, 1, repeat('a', 64), 1, 1, 'upgrade', 1, 'CONSUMED',"
            + " current_timestamp, current_timestamp)",
        publicationId,
        captureHeadId,
        scopeId,
        UUID.fromString("44444444-4444-4444-4444-444444444444"));

    long emptyFormRevision =
        insertFact(jdbc, publicationId, epochId, 9001L, EMPTY_FORM_IID);
    long commentRevision =
        insertFact(jdbc, publicationId, epochId, 9002L, COMMENT_IID);
    long recordedFormRevision =
        insertFact(jdbc, publicationId, epochId, 9003L, RECORDED_FORM_IID);
    jdbc.update(
        "insert into form_review_fact(merge_request_revision_id, source_system, source_instance,"
            + " project_id, merge_request_id, merge_request_iid, record_id, scope_id,"
            + " storage_version_id, reviewer_user_id, reviewer_name, review_duration_minutes,"
            + " specification_count, record_created_at, fact_refreshed_at)"
            + " values (?, 'GITLAB', ?, ?, 9003, ?, 7003, ?, ?, 'u-carol', 'Carol', 20, 2,"
            + " current_timestamp, current_timestamp)",
        recordedFormRevision,
        INSTANCE,
        PROJECT_ID,
        RECORDED_FORM_IID,
        scopeId,
        versionId);
    assertThat(emptyFormRevision).isPositive();
    assertThat(commentRevision).isPositive();
    return captureHeadId;
  }

  private static void insertAuthority(
      JdbcTemplate jdbc, long scopeId, long iid, String authority) {
    jdbc.update(
        "insert into form_resource_authorities(source_config_id, source_instance,"
            + " canonical_gitlab_base_url, project_id, resource_type, resource_id, template_key,"
            + " scope_id, authority, authority_revision, effective_at, created_at, updated_at)"
            + " values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, ?, 1, current_timestamp,"
            + " current_timestamp, current_timestamp)",
        SOURCE_CONFIG_ID,
        INSTANCE,
        URL,
        PROJECT_ID,
        iid,
        scopeId,
        authority);
  }

  private static long insertHead(JdbcTemplate jdbc, long scopeId, long iid) {
    return jdbc.queryForObject(
        "insert into form_change_heads(source_config_id, source_instance,"
            + " canonical_gitlab_base_url, project_id, resource_type, resource_id, template_key,"
            + " scope_id, latest_change_version, consumed_change_version, status, fencing_version,"
            + " created_at, updated_at)"
            + " values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, 1, 1, 'CONSUMED', 1,"
            + " current_timestamp, current_timestamp) returning id",
        Long.class,
        SOURCE_CONFIG_ID,
        INSTANCE,
        URL,
        PROJECT_ID,
        iid,
        scopeId);
  }

  private static long insertFact(
      JdbcTemplate jdbc, UUID publicationId, UUID epochId, long mergeRequestId, long iid) {
    return jdbc.queryForObject(
        "insert into merge_request_fact(source_system, source_instance, project_id,"
            + " merge_request_id, merge_request_iid, title, added_lines, deleted,"
            + " fact_refreshed_at, created_at, updated_at, publication_epoch_id,"
            + " created_by_publication_id, visible_from, visible_to)"
            + " values ('GITLAB', ?, ?, ?, ?, 'upgrade', 100, false, current_timestamp,"
            + " current_timestamp, current_timestamp, ?, ?, 1, null) returning id",
        Long.class,
        INSTANCE,
        PROJECT_ID,
        mergeRequestId,
        iid,
        epochId,
        publicationId);
  }

  private static String authority(JdbcTemplate jdbc, long iid) {
    return jdbc.queryForObject(
        "select a.form_authority from merge_request_fact_authority a"
            + " join merge_request_fact f on f.id = a.merge_request_revision_id"
            + " where f.source_instance = ? and f.project_id = ? and f.merge_request_iid = ?",
        String.class,
        INSTANCE,
        PROJECT_ID,
        iid);
  }

  /** 行快照统一按字符串规范化，避免数组等驱动的实例相等性造成假差异。 */
  private static List<Map<String, String>> snapshot(JdbcTemplate jdbc, String table) {
    return jdbc.query(
        "select * from " + table + " order by 1",
        (rs, rowNum) -> {
          ResultSetMetaData meta = rs.getMetaData();
          Map<String, String> row = new LinkedHashMap<>();
          for (int index = 1; index <= meta.getColumnCount(); index++) {
            Object value = rs.getObject(index);
            row.put(meta.getColumnLabel(index), value == null ? null : String.valueOf(value));
          }
          return row;
        });
  }
}
