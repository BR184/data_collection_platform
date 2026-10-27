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
            + "(id, instance_id, origin_version_id, record_version, score, created_at, updated_at)"
            + " values (9001, ?, ?, 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        instanceId, v2Id);
    // 目录：一条 AUTHORITATIVE（v2）、一条人为 LEGACY_SNAPSHOT 承载（v1，应被排除）。
    // 承载行没有历史账号证据，目录创建人必须同样为空，否则统一读源按身份证据不一致拒绝。
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (9001, ?, ?, ?, 'MATERIALIZED', ?, ?, 'default', ?, 18, 'merge_request', '601',
                null, null,
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
                null, null,
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

  // ===== N1-R03：统一读源必须回表取真实值并排除作废 =====

  @Test
  void shouldReadLegacyDemoValuesInPlaceThroughUnifiedReader() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-reader-legacy", configId, URL, 31, "DRAFT");
    FormV6TestSupport.insertLegacyPair(jdbc, 910001, 910002, URL, 31);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    assertThat(services.tx().execute(status -> services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()))
        .status()).isEqualTo("REGISTERED");

    // 冻结旧列规格只能引用旧表真实物理列。
    List<String> specColumns =
        FormV6TestSupport.parseSpec(
                FormV6RegistrationService.LEGACY_DEMO_FIELD_SPEC, "code_review")
            .fields().stream()
            .map(field -> field.storage().resolvedColumn())
            .toList();
    assertThat(specColumns).isNotEmpty();
    for (String column : specColumns) {
      assertThat(jdbc.queryForObject(
          "select count(*) from information_schema.columns"
              + " where table_name = 'collect_form_records' and column_name = ?",
          Integer.class, column))
          .as("旧 Demo 规格列必须真实存在：" + column)
          .isEqualTo(1);
    }

    // NONE 记录按 source_table 回旧表取原值，不搬迁、不返回空载荷。
    List<FormRecordDirectoryReader.LocatedRecord> hits =
        services.reader().listByResource(scopeId, "merge_request", "910001", 31);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().instanceMode()).isEqualTo("NONE");
    assertThat(hits.getFirst().fields())
        .containsEntry("reviewer", "Alice")
        .containsEntry("specification_score", 1)
        .containsEntry("remark", "legacy");
    assertThat(hits.getFirst().deleted()).isFalse();
    // find 与列表载荷契约一致。
    assertThat(services.reader().find(910001))
        .hasValueSatisfying(record ->
            assertThat(record.fields()).isEqualTo(hits.getFirst().fields()));
    // 第二条旧记录同样可读。
    assertThat(services.reader().listByResource(scopeId, "merge_request", "910002", 31))
        .singleElement()
        .satisfies(record -> assertThat(record.fields()).containsEntry("remark", "second"));
  }

  @Test
  void shouldExcludeVoidedRecordsFromBusinessReadsButKeepLocationEvidence() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-reader-void", configId, URL, 32, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, 32, 9201);
    jdbc.update(
        "insert into " + v1.recordTable()
            + "(id, instance_id, origin_version_id, record_version, score, deleted, created_at, updated_at)"
            + " values (9201, ?, ?, 1, 7, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        instanceId, v1.versionId());
    insertLocation(jdbc, 9201, scopeId, v1, instanceId, configId, "9201", 32, null);

    assertThat(services.reader().find(9201)).isPresent();
    assertThat(services.reader().listByResource(scopeId, "merge_request", "9201", 32)).hasSize(1);
    jdbc.update("update " + v1.recordTable() + " set deleted = true where id = 9201");
    // 作废记录不再是有效业务读源。
    assertThat(services.reader().find(9201)).isEmpty();
    assertThat(services.reader().listByResource(scopeId, "merge_request", "9201", 32)).isEmpty();
    // 显式定位用途：幂等核验仍可定位并看到作废状态。
    assertThat(services.reader().findIncludingVoided(9201))
        .hasValueSatisfying(record -> assertThat(record.deleted()).isTrue());

    // NONE 侧同样排除作废旧行，但登记证据保持完整。
    long legacyScopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-reader-void-legacy", configId, URL, 33, "DRAFT");
    FormV6TestSupport.insertLegacyRow(
        jdbc, 930001, URL, 33, 930001, "Alice", 30, 1, 2, 3, 4, 5, "legacy", false);
    FormV6TestSupport.insertLegacyRow(
        jdbc, 930002, URL, 33, 930002, "Bob", 45, 6, 7, 8, 9, 10, "voided", true);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(legacyScopeId);
    assertThat(precheck.count()).isEqualTo(2);
    services.tx().execute(status -> services.registrationService().registerLegacyDemo(
        legacyScopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()));
    assertThat(services.reader().listByResource(legacyScopeId, "merge_request", "930002", 33))
        .isEmpty();
    assertThat(services.reader().listByResource(legacyScopeId, "merge_request", "930001", 33))
        .hasSize(1);
    // 作废行仍是登记证据的一部分：重入按已完成登记处理，不报不完整。
    assertThat(services.registrationService().precheckLegacyDemo(legacyScopeId).alreadyRegistered())
        .isTrue();
  }

  @Test
  void shouldRejectBusinessReadWhenBackTableEvidenceMismatch() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-reader-evidence", configId, URL, 34, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, 34, 9301);
    jdbc.update(
        "insert into " + v1.recordTable()
            + "(id, instance_id, origin_version_id, record_version, score, created_at, updated_at)"
            + " values (9301, ?, ?, 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        instanceId, v1.versionId());
    insertLocation(jdbc, 9301, scopeId, v1, instanceId, configId, "9301", 34, null);
    assertThat(services.reader().find(9301)).isPresent();
    // 缺行：目录指向不存在的承载行，明确拒绝而不是返回空载荷。
    jdbc.update("insert into " + v1.recordTable()
        + "(id, instance_id, origin_version_id, record_version, created_at, updated_at)"
        + " values (9302, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        instanceId, v1.versionId());
    insertLocation(jdbc, 9302, scopeId, v1, instanceId, configId, "9302", 34, null);
    jdbc.update("delete from " + v1.recordTable() + " where id = 9302");
    assertThatThrownBy(() -> services.reader().find(9302))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("回表");
    // 完整C不一致：目录项目与实例项目不同。
    jdbc.update("update form_record_locations set project_id = 35 where record_id = 9301");
    assertThatThrownBy(() -> services.reader().listByResource(scopeId, "merge_request", "9301", 35))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("完整资源");
    // 创建人证据不一致。
    jdbc.update("update form_record_locations set project_id = 34, created_by = 'mallory'"
        + " where record_id = 9301");
    assertThatThrownBy(() -> services.reader().find(9301))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("创建人");
  }

  /** 手工目录行夹具：业务值仍由 reader 回表读取。 */
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

  private FormV6TestSupport.WiredServices wired() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    return FormV6TestSupport.wire(jdbc);
  }
}
