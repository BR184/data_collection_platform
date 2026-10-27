package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.service.form.FormV6TestSupport.FixtureTables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * N1-R04 真实非空升级：先把独立库迁到 {@code 20261022.03}（159 条），写入真实非空旧 Demo 与
 * v5 多副本数据并冻结原行/规格/首次指纹/权限/序列证据，再执行第 160 条迁移，核验既有行零改动、
 * 新结构形状与角色默认值，并在升级后的库上完成一次真实登记与统一读源。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormV6LegacyUpgradeIntegrationTest {

  private static final String URL = "http://gitlab.n1-upgrade.local";
  private static final String PRE_V6_HEAD = "20261022.03";
  private static final String V6_HEAD = "20261027.01";
  private static final FormActorContext ALICE =
      new FormActorContext("alice", "Alice", java.util.Set.of());

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void shouldUpgradeRealNonEmptyLegacyDatabaseAndConserveEveryExistingRow() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    // 1) 只升到 159 条：v6 目录对象此时必须还不存在。
    MigrateResult preV6 = flyway(dataSource, PRE_V6_HEAD).migrate();
    assertThat(preV6.migrationsExecuted).isEqualTo(159);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("select to_regclass('form_record_locations')", String.class))
        .isNull();
    assertThat(headVersion(jdbc)).isEqualTo(PRE_V6_HEAD);

    // 2) 真实非空旧库数据：旧 Demo 行、v5 两版本多副本、首次指纹与活跃范围指针。
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long v5Scope = FormV6TestSupport.insertScope(
        jdbc, "code_review", "upgrade-v5", configId, URL, 41, "DRAFT");
    FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, v5Scope, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, v5Scope, "code_review", "V2", FormV6TestSupport.SPEC_V2, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, v5Scope, v2.versionId());
    String firstHash = upgradeFingerprint(jdbc, v5Scope, v1.versionId(), 41, 9401);
    long originInstance = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), v5Scope, configId, URL, 41, 9401);
    // 承载版本 v2 的冻结规格已删除 note 字段，因此历史副本不得携带该列的非空值。
    long copiedRecord = FormV6TestSupport.insertRecord(
        jdbc, v1.recordTable(), originInstance, v1.versionId(), ALICE.userId(),
        "0b7a5d1e-0000-4000-8000-0000000000c1", firstHash, 1, false, "3", null);
    long carrierInstance = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), v5Scope, configId, URL, 41, 9401);
    jdbc.update(
        "insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version, deleted, score, created_at, updated_at)"
            + " select id, ?, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version + 1, deleted, score, created_at,"
            + " updated_at + interval '1 hour' from " + v1.recordTable() + " where id = ?",
        carrierInstance, copiedRecord);
    long legacyScope = FormV6TestSupport.insertScope(
        jdbc, "code_review", "upgrade-legacy", configId, URL, 42, "DRAFT");
    FormV6TestSupport.insertLegacyPair(jdbc, 950001, 950002, URL, 42);

    List<Map<String, String>> legacyBefore = snapshotRows(jdbc, "collect_form_records");
    List<Map<String, String>> v1Before = snapshotRows(jdbc, v1.recordTable());
    List<Map<String, String>> v2Before = snapshotRows(jdbc, v2.recordTable());
    List<Map<String, Object>> specsBefore = jdbc.queryForList(
        "select id, scope_id, field_spec::text as field_spec, version_status,"
            + " instance_table, record_table from form_template_versions order by id");
    List<String> historyBefore = jdbc.queryForList(
        "select version || ':' || coalesce(checksum::text,'-') from flyway_schema_history"
            + " where version is not null order by installed_rank", String.class);
    Map<String, Object> sequenceBefore = jdbc.queryForMap(
        "select last_value, is_called from form_record_id_seq");
    long instancesBefore = count(jdbc, v1.instanceTable()) + count(jdbc, v2.instanceTable());

    // 3) 执行第 160 条迁移：真实非空升级路径。
    MigrateResult upgraded = flyway(dataSource, null).migrate();
    assertThat(upgraded.migrationsExecuted).isEqualTo(1);
    assertThat(headVersion(jdbc)).isEqualTo(V6_HEAD);
    assertThat(jdbc.queryForObject("select to_regclass('form_record_locations')", String.class))
        .isNotNull();

    // 4) 既有行、规格、权限与序列逐字节守恒。
    assertThat(snapshotRows(jdbc, "collect_form_records")).isEqualTo(legacyBefore);
    assertThat(snapshotRows(jdbc, v1.recordTable())).isEqualTo(v1Before);
    assertThat(snapshotRows(jdbc, v2.recordTable())).isEqualTo(v2Before);
    assertThat(jdbc.queryForList(
        "select id, scope_id, field_spec::text as field_spec, version_status,"
            + " instance_table, record_table from form_template_versions order by id"))
        .isEqualTo(specsBefore);
    assertThat(jdbc.queryForList(
        "select version || ':' || coalesce(checksum::text,'-') from flyway_schema_history"
            + " where version is not null order by installed_rank limit 159", String.class))
        .isEqualTo(historyBefore);
    assertThat(jdbc.queryForMap("select last_value, is_called from form_record_id_seq"))
        .isEqualTo(sequenceBefore);
    assertThat(count(jdbc, v1.instanceTable()) + count(jdbc, v2.instanceTable()))
        .isEqualTo(instancesBefore);
    assertThat(jdbc.queryForObject("select count(*) from form_record_locations", Long.class))
        .isZero();
    // 角色与形状默认值：既有 RECORDS 版本保持权威且实例化，来源表为空。
    List<Map<String, Object>> roles = jdbc.queryForList(
        "select record_source_role, instance_mode, source_table from form_template_versions"
            + " where scope_id = ? order by id", v5Scope);
    assertThat(roles).hasSize(2);
    for (Map<String, Object> role : roles) {
      assertThat(role.get("record_source_role")).isEqualTo("AUTHORITATIVE");
      assertThat(role.get("instance_mode")).isEqualTo("MATERIALIZED");
      assertThat(role.get("source_table")).isNull();
    }
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        v5Scope)).isZero();
    // 范围写栅栏约束、旧行可选：形状约束对新版本生效。
    assertThat(jdbc.queryForObject(
        "select count(*) from pg_constraint where conrelid = 'form_template_versions'::regclass"
            + " and conname in ('ck_ftv_role', 'ck_ftv_instance_mode', 'ck_ftv_instance_shape')",
        Integer.class)).isEqualTo(3);

    // 5) 升级库立即可用：路径 B 真实登记 + 统一读源取回原值。
    FormV6TestSupport.WiredServices services = FormV6TestSupport.wire(jdbc);
    FormV6RegistrationService.PrecheckReport carrierPrecheck =
        services.registrationService().precheckCopiedRecords(v5Scope);
    assertThat(carrierPrecheck.count()).isEqualTo(1);
    assertThat(
            services.tx().execute(status -> services.registrationService().registerCopiedRecords(
                    v5Scope, "operator", carrierPrecheck.count(), carrierPrecheck.rowHash()))
                .status())
        .isEqualTo("REGISTERED");
    assertThat(FormV6TestSupport.directoryRows(jdbc, v5Scope)).singleElement().satisfies(row -> {
      assertThat(row.get("record_id")).isEqualTo(copiedRecord);
      assertThat(row.get("storage_version_id")).isEqualTo(v2.versionId());
      assertThat(row.get("origin_version_id")).isEqualTo(v1.versionId());
      assertThat(row.get("create_payload_hash")).isEqualTo(firstHash);
    });
    // 原行仍未被改写。
    assertThat(snapshotRows(jdbc, v1.recordTable())).isEqualTo(v1Before);
    assertThat(services.reader().listByResource(v5Scope, "merge_request", "9401", 41))
        .singleElement()
        .satisfies(record -> assertThat(record.fields()).containsEntry("score", 3));

    // 路径 A 在同一升级库上原位登记旧 Demo。
    FormV6RegistrationService.PrecheckReport legacyPrecheck =
        services.registrationService().precheckLegacyDemo(legacyScope);
    assertThat(legacyPrecheck.count()).isEqualTo(2);
    assertThat(
            services.tx().execute(status -> services.registrationService().registerLegacyDemo(
                    legacyScope, "operator", "LEGACY-DEMO", legacyPrecheck.count(),
                    legacyPrecheck.rowHash()))
                .status())
        .isEqualTo("REGISTERED");
    assertThat(snapshotRows(jdbc, "collect_form_records")).isEqualTo(legacyBefore);
    assertThat(services.reader().listByResource(legacyScope, "merge_request", "950001", 42))
        .singleElement()
        .satisfies(record -> assertThat(record.fields()).containsEntry("remark", "legacy"));
  }

  /** 行快照统一按字符串规范化，避免 {@code java.sql.Array} 的实例相等性造成假差异。 */
  private static List<Map<String, String>> snapshotRows(JdbcTemplate jdbc, String table) {
    return jdbc.query(
        "select * from " + table + " order by id",
        (rs, rowNum) -> {
          ResultSetMetaData meta = rs.getMetaData();
          Map<String, String> row = new LinkedHashMap<>();
          for (int index = 1; index <= meta.getColumnCount(); index++) {
            Object value = rs.getObject(index);
            if (value instanceof java.sql.Array array) {
              try {
                Object raw = array.getArray();
                if (raw instanceof Object[] items) {
                  List<String> parts = new java.util.ArrayList<>();
                  for (Object item : items) {
                    parts.add(item == null ? "" : String.valueOf(item));
                  }
                  value = String.join(",", parts);
                } else {
                  value = null;
                }
              } catch (java.sql.SQLException error) {
                throw new IllegalStateException("数组列快照失败", error);
              }
            }
            row.put(meta.getColumnLabel(index), value == null ? null : String.valueOf(value));
          }
          return row;
        });
  }

  private static long count(JdbcTemplate jdbc, String table) {
    Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }

  private static String headVersion(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "select version from flyway_schema_history where version is not null"
            + " order by installed_rank desc limit 1",
        String.class);
  }

  private Flyway flyway(DriverManagerDataSource dataSource, String target) {
    org.flywaydb.core.api.configuration.FluentConfiguration configuration =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    if (target != null) {
      configuration = configuration.target(target);
    }
    return configuration.load();
  }

  /** 按 v1 冻结规格与 v1 版本身份计算首次指纹，登记后不得被升级改写。 */
  private static String upgradeFingerprint(
      JdbcTemplate jdbc, long scopeId, long versionId, long projectId, long resourceId) {
    ObjectMapper mapper = new ObjectMapper();
    FormV6TestSupport.WiredServices services = FormV6TestSupport.wire(jdbc);
    FormVersionContext context =
        FormV6TestSupport.contextFor(jdbc, mapper, services.contextService(), scopeId, versionId,
            projectId, resourceId);
    Map<String, JsonNode> fields =
        Map.of("score", JsonNodeFactory.instance.numberNode(3));
    var canonical = services.fieldValueService().validateForReplay(context, fields);
    return services.fieldValueService().fingerprint(context, ALICE.userId(),
        canonical.canonicalFields());
  }
}
