package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 稳定语义登记（IV-01 正式化）与统一内部读源：实际类型校验、身份冲突拒绝、
 * reader 的角色/来源隔离与源集合捕获。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormComparableRegistrationIntegrationTest {

  private static final String URL = "http://gitlab.n1.local";

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void shouldRegisterDeclarationsOnlyWhenTypeMatchesFrozenSpec() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-compare", configId, URL, 16, "DRAFT");
    FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    long versionId = jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ?", Long.class, scopeId);

    FormComparableRegistrationService.RegistrationResult ok = services.tx().execute(status ->
        services.comparableService().register(
            scopeId, versionId,
            List.of(
                new FormComparableRegistrationService.FieldDeclaration(
                    "得分", "fld_score", "code_review.score", "integer", "count", "规范问题数"),
                new FormComparableRegistrationService.FieldDeclaration(
                    "补充", "fld_note", "common.supplement_note", "string", null, "补充说明")),
            "operator"));
    assertThat(ok.status()).isEqualTo("REGISTERED");
    // 幂等重入：同参数 UNCHANGED。
    FormComparableRegistrationService.RegistrationResult unchanged = services.tx().execute(status ->
        services.comparableService().register(
            scopeId, versionId,
            List.of(
                new FormComparableRegistrationService.FieldDeclaration(
                    "得分", "fld_score", "code_review.score", "integer", "count", "规范问题数"),
                new FormComparableRegistrationService.FieldDeclaration(
                    "补充", "fld_note", "common.supplement_note", "string", null, "补充说明")),
            "operator"));
    assertThat(unchanged.status()).isEqualTo("UNCHANGED");
    Map<String, FormComparableRegistrationService.FieldDeclaration> read =
        services.comparableService().declarationsForVersion(versionId);
    assertThat(read).containsKeys("得分", "补充");
    assertThat(read.get("得分").declaredType()).isEqualTo("integer");
  }

  @Test
  void shouldRejectDeclarationConflictUnknownTypeAndIdentityCollisions() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-compare-bad", configId, URL, 17, "DRAFT");
    FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    long versionId = jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ?", Long.class, scopeId);

    // IV-01：登记声明 integer 与冻结实际类型 integer 一致才有效；声明 string 冲突拒绝。
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.comparableService().register(
                scopeId, versionId,
                List.of(new FormComparableRegistrationService.FieldDeclaration(
                    "得分", "fld_score", "code_review.score", "string", null, "语义漂移")),
                "operator")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("TYPE_DECLARATION_CONFLICT");
    // 未知类型拒绝。
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.comparableService().register(
                scopeId, versionId,
                List.of(new FormComparableRegistrationService.FieldDeclaration(
                    "得分", "fld_score", "code_review.score", "decimal", null, "未知")),
                "operator")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("未知声明类型");
    // 声明字段不在冻结规格中。
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.comparableService().register(
                scopeId, versionId,
                List.of(new FormComparableRegistrationService.FieldDeclaration(
                    "不存在", "fld_x", "code_review.x", "integer", null, "无")),
                "operator")))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class);
    // 合法登记后，stableId 冲突绑定其它字段被拒绝。
    services.tx().execute(status -> services.comparableService().register(
        scopeId, versionId,
        List.of(new FormComparableRegistrationService.FieldDeclaration(
            "得分", "fld_score", "code_review.score", "integer", "count", "规范问题数")),
        "operator"));
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.comparableService().register(
                scopeId, versionId,
                List.of(new FormComparableRegistrationService.FieldDeclaration(
                    "补充", "fld_score", "common.supplement_note", "string", null, "重复身份")),
                "operator")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("stableId 或 semanticKey 已绑定其它字段");
    // 已登记字段的不同声明拒绝覆盖。
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.comparableService().register(
                scopeId, versionId,
                List.of(new FormComparableRegistrationService.FieldDeclaration(
                    "得分", "fld_score", "code_review.score", "integer", "hour", "单位漂移")),
                "operator")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("拒绝覆盖");
  }

  @Test
  void shouldExposeOnlyAuthoritativeVersionsAndIsolateFullResourceC() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-reader", configId, URL, 18, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", FormV6TestSupport.SPEC_V2, "PUBLISHED", true);
    long v1Id = jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ? and record_table = ?",
        Long.class, scopeId, v1.recordTable());
    long v2Id = jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ? and record_table = ?",
        Long.class, scopeId, v2.recordTable());
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, 18, 601);
    jdbc.update(
        "insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, record_version, score) values (9001, ?, ?, 1, 7)",
        instanceId, v2Id);
    // 目录：一条 AUTHORITATIVE（v2）、一条人为 LEGACY_SNAPSHOT 承载（v1，应被排除）。
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (9001, ?, ?, ?, 'MATERIALIZED', ?, ?, 'default', ?, 18, 'merge_request', '601',
                null, 'alice',
                (select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?),
                null, current_timestamp)
        """,
        scopeId, v2Id, v2Id, instanceId, configId, URL, v2Id);
    jdbc.update(
        "update form_template_versions set record_source_role = 'LEGACY_SNAPSHOT' where id = ?",
        v1Id);
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (9002, ?, ?, ?, 'MATERIALIZED', ?, ?, 'default', ?, 18, 'merge_request', '601',
                null, 'alice',
                (select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?),
                null, current_timestamp)
        """,
        scopeId, v1Id, v1Id, instanceId, configId, URL, v1Id);

    // find：快照承载的记录排除。
    assertThat(services.reader().find(9001)).isPresent();
    assertThat(services.reader().find(9002)).isEmpty();
    assertThat(services.reader().find(9999)).isEmpty();
    // listByResource：完整C隔离，仅权威版本命中。
    List<FormRecordDirectoryReader.LocatedRecord> hits =
        services.reader().listByResource(scopeId, "merge_request", "601", 18);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().recordId()).isEqualTo(9001L);
    assertThat(hits.getFirst().fields()).containsEntry("score", 7);
    assertThat(services.reader().listByResource(scopeId, "merge_request", "602", 18)).isEmpty();
    assertThat(services.reader().listByResource(scopeId, "issue", "601", 18)).isEmpty();
    // 捕获源集合：仅权威版本、修订与哈希与范围列一致。
    jdbc.update(
        "update form_template_scopes set source_set_hash = form_source_set_hash(id)"
            + " where id = ?",
        scopeId);
    FormRecordDirectoryReader.SourceSet capture = services.reader().captureSourceSet(scopeId);
    assertThat(capture.revision()).isZero();
    assertThat(capture.hash()).isEqualTo(jdbc.queryForObject(
        "select source_set_hash from form_template_scopes where id = ?", String.class, scopeId));
    assertThat(capture.versions()).hasSize(1);
    assertThat(capture.versions().getFirst().versionId()).isEqualTo(v2Id);
    assertThat(capture.versions().getFirst().fieldSpecHash()).hasSize(64);
    // 缺失范围：显式异常，不静默猜测。
    assertThatThrownBy(() -> services.reader().captureSourceSet(999999))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("表单范围不存在");
  }

  private FormV6TestSupport.WiredServices wired() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    return FormV6TestSupport.wire(jdbc);
  }
}
