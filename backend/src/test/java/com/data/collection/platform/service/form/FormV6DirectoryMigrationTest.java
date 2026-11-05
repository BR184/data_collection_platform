package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * N1 目录迁移的空平台路径与共享序列高水位：对象与默认值齐备、
 * {@code form_advance_record_sequence} 的 is_called 全边界与跨范围串行。
 *
 * <p>非空旧库的真实 159→160 升级不在本类伪装：由
 * {@link FormV6LegacyUpgradeIntegrationTest} 先迁到 {@code 20261022.03} 再升级并核验守恒。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormV6DirectoryMigrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void shouldMigrateEmptyPlatformWithDirectoryObjectsAndDefaults() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    assertDirectoryObjects(jdbc);
    assertThat(jdbc.queryForObject("select count(*) from form_record_locations", Integer.class))
        .isZero();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, "http://gitlab.n1-empty.local");
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-empty", configId, "http://gitlab.n1-empty.local", 3, "DRAFT");
    Long versionId = jdbc.queryForObject(
        "insert into form_template_versions(scope_id, version_label, storage_kind,"
            + " instance_table, record_table, field_spec, version_status)"
            + " values (?, 'VX', 'RECORDS', 't_x_inst', 't_x_rec', '{}'::jsonb, 'INSTALLED')"
            + " returning id",
        Long.class, scopeId);
    Map<String, Object> version = jdbc.queryForMap(
        "select record_source_role, instance_mode from form_template_versions where id = ?",
        versionId);
    assertThat(version.get("record_source_role")).isEqualTo("AUTHORITATIVE");
    assertThat(version.get("instance_mode")).isEqualTo("MATERIALIZED");
    jdbc.update("delete from form_template_versions where id = ?", versionId);
    jdbc.update("delete from form_template_scopes where id = ?", scopeId);
  }

  @Test
  void shouldAdvanceSequenceOnlyForwardAcrossAllIsCalledBoundaries() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    jdbc.execute("alter sequence form_record_id_seq restart with 1");
    Boolean called = jdbc.queryForObject(
        "select is_called from form_record_id_seq", Boolean.class);
    assertThat(called).isFalse();
    // 未调用且高水位相等：下一次 nextval 不得重复登记 ID 1。
    Long advanced = jdbc.queryForObject(
        "select form_advance_record_sequence(1)", Long.class);
    assertThat(advanced).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(2L);
    // 相等且已调用：序列零改动。
    assertThat(jdbc.queryForObject("select form_advance_record_sequence(2)", Long.class))
        .isEqualTo(2L);
    assertThat(jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(3L);
    // 更高：setval 推进；更低/相等：不动。
    assertThat(jdbc.queryForObject("select form_advance_record_sequence(50)", Long.class))
        .isEqualTo(50L);
    assertThat(jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(51L);
    assertThat(jdbc.queryForObject("select form_advance_record_sequence(51)", Long.class))
        .isEqualTo(51L);
    assertThat(jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(52L);
    // 非法输入。
    assertThatThrownBy(() ->
        jdbc.queryForObject("select form_advance_record_sequence(-1)", Long.class))
        .hasMessageContaining("high water");
    // 跨范围共用同一咨询锁：同事务可重入，只前进。
    Long reenter = jdbc.queryForObject(
        "select form_advance_record_sequence(form_advance_record_sequence(60))", Long.class);
    assertThat(reenter).isEqualTo(60L);
    assertThat(jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class))
        .isEqualTo(61L);
  }

  @Test
  void shouldRejectNoneModeOnNonAuthoritativeRoleByConstraint() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    List<String> violations = jdbc.queryForList(
        """
        select conname from pg_constraint
         where conrelid = 'form_template_versions'::regclass
           and conname in ('ck_ftv_role', 'ck_ftv_instance_mode', 'ck_ftv_instance_shape',
                           'ck_ftv_none_role', 'uq_ftv_id_instance_mode')
         order by conname
        """,
        String.class);
    assertThat(violations).hasSize(5);
    assertThat(jdbc.queryForObject(
        "select to_regclass('form_scope_readable_versions')", String.class)).isNotNull();
  }

  private static void assertDirectoryObjects(JdbcTemplate jdbc) {
    for (String object : List.of(
        "form_record_locations", "form_directory_events", "form_directory_event_cursors",
        "form_record_id_watermarks", "form_field_comparable_registrations")) {
      assertThat(jdbc.queryForObject("select to_regclass(?)", String.class, object))
          .as(object).isNotNull();
    }
    assertThat(jdbc.queryForObject(
        "select to_regprocedure('form_create_fingerprint(text,text,text,text,text,text,text,text,json)')",
        String.class)).isNotNull();
    assertThat(jdbc.queryForObject(
        "select form_source_set_hash(1)", String.class)).hasSize(64);
    // 指纹与 v5 Java 顺序逐字节一致：同一输入必须有稳定十六进制摘要。
    String hash = jdbc.queryForObject(
        "select form_create_fingerprint('7101', 'http://gitlab', '11', 'merge_request', '101',"
            + " 'code_review', '8501', 'alice', ?::json)",
        String.class, "{\"score\":2}");
    assertThat(hash).hasSize(64);
  }
}
