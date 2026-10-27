package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * v6 受控原位登记与跨版重放：路径 A/B 守恒与拒绝矩阵、创建写目录、
 * origin/storage 不同时的首次指纹核验。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormV6RegistrationIntegrationTest {

  private static final String URL = "http://gitlab.n1.local";

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  private static final FormActorContext ALICE =
      new FormActorContext("alice", "Alice", java.util.Set.of());
  private static final FormActorContext BOB =
      new FormActorContext("bob", "Bob", java.util.Set.of());

  @Test
  void shouldRegisterLegacyDemoInPlaceWithConservationAndIdempotency() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-legacy", configId, URL, 11, "DRAFT");
    insertLegacyRows(jdbc, 201, 202, 11);

    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    assertThat(precheck.count()).isEqualTo(2);
    assertThat(precheck.alreadyRegistered()).isFalse();

    FormV6RegistrationService.RegistrationResult result = services.tx().execute(status ->
        services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()));
    assertThat(result.status()).isEqualTo("REGISTERED");
    assertThat(result.directoryRows()).isEqualTo(2);

    List<Map<String, Object>> directory = FormV6TestSupport.directoryRows(jdbc, scopeId);
    assertThat(directory).hasSize(2);
    Map<String, Object> first = directory.getFirst();
    assertThat(first.get("record_id")).isEqualTo(201L);
    assertThat(first.get("storage_version_id")).isEqualTo(first.get("origin_version_id"));
    assertThat(first.get("instance_mode")).isEqualTo("NONE");
    assertThat(first.get("instance_id")).isNull();
    assertThat(first.get("request_key")).isNull();
    assertThat(first.get("create_payload_hash")).isNull();
    assertThat(first.get("created_by")).isNull();
    assertThat(first.get("gitlab_base_url")).isEqualTo(URL);
    assertThat(first.get("project_id")).isEqualTo(11L);
    String specHash = jdbc.queryForObject(
        "select form_spec_hash(?::jsonb)::char(64)", String.class,
        FormV6RegistrationService.LEGACY_DEMO_FIELD_SPEC);
    assertThat(first.get("origin_spec_hash")).isEqualTo(specHash);
    // 原行守恒。
    assertThat(jdbc.queryForObject(
        "select reviewer from collect_form_records where id = 201", String.class))
        .isEqualTo("Alice");
    // 修订、事件、水位与序列。
    int revision = jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        scopeId);
    assertThat(revision).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select event_type from form_directory_events where scope_id = ?", String.class,
        scopeId)).isEqualTo("LEGACY_REGISTERED");
    assertThat(jdbc.queryForObject(
        "select high_water from form_record_id_watermarks where source_ref = 'collect_form_records'",
        Long.class)).isEqualTo(202L);
    long nextId = jdbc.queryForObject("select nextval('form_record_id_seq')", Long.class);
    assertThat(nextId).isGreaterThan(202L);
    // 幂等重入：同源同哈希不增修订、不建新版本。
    FormV6RegistrationService.RegistrationResult reentry = services.tx().execute(status ->
        services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()));
    assertThat(reentry.status()).isEqualTo("ALREADY_REGISTERED");
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        scopeId)).isEqualTo(revision);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_template_versions where scope_id = ?", Integer.class,
        scopeId)).isEqualTo(1);
  }

  @Test
  void shouldRejectLegacyDemoOnSourceDriftAndDuplicateOwnership() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-legacy-drift", configId, URL, 12, "DRAFT");
    insertLegacyRows(jdbc, 211, 212, 12);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    // precheck 与 register 之间源变化：整体拒绝，零写入。
    jdbc.update("update collect_form_records set remark = 'changed' where id = 211");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerLegacyDemo(
                scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash())))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("precheck与register之间源已变化");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isZero();
    // 重复归属：另一范围声明覆盖同一行后，原范围 precheck 即整体拒绝。
    FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-legacy-other", configId, URL, 12, "DRAFT");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("重复归属");
  }

  @Test
  void shouldRegisterV5CarrierWithOriginEvidenceAndLegacySnapshotRoles() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-carrier", configId, URL, 13, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", FormV6TestSupport.SPEC_V2, "PUBLISHED", true);
    long v1Id = versionId(jdbc, scopeId, v1.recordTable());
    long v2Id = versionId(jdbc, scopeId, v2.recordTable());
    FormV6TestSupport.activateScope(jdbc, scopeId, v2Id);
    // v1 创建（首次指纹按 v1 规格与 v1 版本身份计算），迁移复制到 v2（承载）。
    String firstHash = fingerprint(services, scopeId, v1Id, 13, 301, ALICE, "3", "首个");
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, 13, 301);
    long recordId = FormV6TestSupport.insertRecord(
        jdbc, v1.recordTable(), instanceId, v1Id, ALICE.userId(),
        "0b7a5d1e-0000-4000-8000-000000000001", firstHash, 1, false, "3", null);
    long carrierInstanceId = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, 13, 301);
    jdbc.update(
        "insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version, deleted, score, created_at, updated_at)"
            + " select id, ?, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version + 1, deleted, score, created_at,"
            + " current_timestamp from " + v1.recordTable() + " where id = ?",
        carrierInstanceId, recordId);
    // 旧副本的 note 字段在承载规格中已删除，但为空值——不构成字段丢失。
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckCopiedRecords(scopeId);
    assertThat(precheck.count()).isEqualTo(1);
    FormV6RegistrationService.RegistrationResult result = services.tx().execute(status ->
        services.registrationService().registerCopiedRecords(
            scopeId, "operator", precheck.count(), precheck.rowHash()));
    assertThat(result.status()).isEqualTo("REGISTERED");
    assertThat(result.versionId()).isEqualTo(v2Id);

    Map<String, Object> directory = FormV6TestSupport.directoryRows(jdbc, scopeId).getFirst();
    assertThat(directory.get("record_id")).isEqualTo(recordId);
    assertThat(directory.get("storage_version_id")).isEqualTo(v2Id);
    assertThat(directory.get("origin_version_id")).isEqualTo(v1Id);
    assertThat(directory.get("request_key")).isEqualTo("0b7a5d1e-0000-4000-8000-000000000001");
    assertThat(directory.get("created_by")).isEqualTo(ALICE.userId());
    assertThat(directory.get("create_payload_hash")).isEqualTo(firstHash);
    String v1SpecHash = jdbc.queryForObject(
        "select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?",
        String.class, v1Id);
    assertThat(directory.get("origin_spec_hash")).isEqualTo(v1SpecHash);
    // 角色：承载 AUTHORITATIVE，其余 LEGACY_SNAPSHOT，不改行不删表。
    assertThat(jdbc.queryForObject(
        "select record_source_role from form_template_versions where id = ?", String.class,
        v2Id)).isEqualTo("AUTHORITATIVE");
    assertThat(jdbc.queryForObject(
        "select record_source_role from form_template_versions where id = ?", String.class,
        v1Id)).isEqualTo("LEGACY_SNAPSHOT");
    assertThat(jdbc.queryForObject(
        "select count(*) from " + v1.recordTable(), Integer.class)).isEqualTo(1);
    // 原行与首次指纹守恒：v1 行值不变。
    assertThat(jdbc.queryForObject(
        "select create_payload_hash from " + v1.recordTable() + " where id = ?",
        String.class, recordId)).isEqualTo(firstHash);
  }

  @Test
  void shouldRejectCarrierWhenEvidenceMissingOrVersionsSplit() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-carrier-bad", configId, URL, 14, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", FormV6TestSupport.SPEC_V2, "PUBLISHED", true);
    long v2Id = versionId(jdbc, scopeId, v2.recordTable());
    FormV6TestSupport.activateScope(jdbc, scopeId, v2Id);
    // request_key 存在但首次指纹缺失：409 阻断，不补写。
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, 14, 401);
    FormV6TestSupport.insertRecord(
        jdbc, v2.recordTable(), instanceId, v2Id, ALICE.userId(),
        "0b7a5d1e-0000-4000-8000-000000000002", null, 1, false, "5", null);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckCopiedRecords(scopeId);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                scopeId, "operator", precheck.count(), precheck.rowHash())))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("首次指纹或账号证据缺失");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isZero();
    // 承载副本不在当前版本：v1 只读副本成为唯一承载而当前版本是 v2。
    JdbcTemplate fresh = services.jdbc();
    fresh.update("update " + v2.recordTable()
        + " set request_key = null, create_payload_hash = null, created_by = null");
    long v1Id = versionId(fresh, scopeId, v1.recordTable());
    long staleInstance = FormV6TestSupport.insertInstance(
        fresh, v1.instanceTable(), scopeId, configId, URL, 14, 402);
    fresh.update(
        "insert into " + v1.recordTable()
            + "(id, instance_id, origin_version_id, record_version, score, created_by)"
            + " values (502, ?, ?, 1, 6, ?)",
        staleInstance, v1Id, ALICE.userId());
    FormV6RegistrationService.PrecheckReport moved =
        services.registrationService().precheckCopiedRecords(scopeId);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                scopeId, "operator", moved.count(), moved.rowHash())))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("承载副本未全部位于当前版本");
  }

  @Test
  void shouldCreateWriteDirectoryAndReplayAcrossVersionsByOriginEvidence() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-create", configId, URL, 15, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    long v1Id = versionId(jdbc, scopeId, v1.recordTable());
    FormV6TestSupport.activateScope(jdbc, scopeId, v1Id);
    FormVersionContext context = FormV6TestSupport.contextFor(
        jdbc, services.objectMapper(), services.contextService(), scopeId, v1Id, 15, 501);

    // 六步创建：目录行同事务写入。
    Map<String, JsonNode> fields = Map.of(
        "score", jsonInt(2),
        "note", json("新建"));
    String payloadHash = fingerprint(services, scopeId, v1Id, 15, 501, ALICE, "2", "新建");
    FormCreateResponse created = services.tx().execute(status -> services.writeService().create(
        context, values(services, context, fields), ALICE, "Alice",
        "0b7a5d1e-0000-4000-8000-000000000003", fields, payloadHash, true, "127.0.0.1", "junit"));
    assertThat(created.outcome()).isEqualTo("CREATED");
    long recordId = Long.parseLong(created.record().id());
    List<Map<String, Object>> directory = FormV6TestSupport.directoryRows(jdbc, scopeId);
    assertThat(directory).hasSize(1);
    assertThat(directory.getFirst().get("record_id")).isEqualTo(recordId);
    assertThat(directory.getFirst().get("storage_version_id")).isEqualTo(v1Id);
    assertThat(directory.getFirst().get("origin_version_id")).isEqualTo(v1Id);
    assertThat(directory.getFirst().get("request_key"))
        .isEqualTo("0b7a5d1e-0000-4000-8000-000000000003");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_audit_logs where record_id = ? and operation = 'CREATE'",
        Integer.class, recordId)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_change_heads where scope_id = ?", Integer.class, scopeId))
        .isEqualTo(1);

    // 模拟发布迁移：v2 成为当前版本，记录复制到 v2，目录 storage 指向 v2、origin 保留 v1。
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", FormV6TestSupport.SPEC_V2, "PUBLISHED", true);
    long v2Id = versionId(jdbc, scopeId, v2.recordTable());
    long carrierInstance = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, 15, 501);
    jdbc.update(
        "insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version, deleted, score, created_at, updated_at)"
            + " select id, ?, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version + 1, deleted, score, created_at,"
            + " current_timestamp from " + v1.recordTable(),
        carrierInstance);
    jdbc.update(
        "update form_record_locations set storage_version_id = ? where record_id = ?",
        v2Id, recordId);
    jdbc.update("update form_template_scopes set current_version_id = ? where id = ?", v2Id,
        scopeId);
    FormVersionContext v2Context = FormV6TestSupport.contextFor(
        jdbc, services.objectMapper(), services.contextService(), scopeId, v2Id, 15, 501);

    // 旧 key 重放：请求版本 v2，但指纹必须按 origin v1 与 v1 冻结规格复算。
    FormCreateResponse replayed = services.directoryService().replayIfPresent(
        v2Context, v2Id, fields, ALICE, "0b7a5d1e-0000-4000-8000-000000000003");
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(Long.parseLong(replayed.record().id())).isEqualTo(recordId);
    // 零新增：记录/审计/head 均不变。
    assertThat(jdbc.queryForObject(
        "select count(*) from " + v2.recordTable(), Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_audit_logs", Integer.class)).isEqualTo(1);

    // 当前编辑值必须 PAYLOAD_MISMATCH。
    Map<String, JsonNode> edited = Map.of(
        "score", jsonInt(9));
    assertThatThrownBy(() -> services.directoryService().replayIfPresent(
        v2Context, v2Id, edited, ALICE, "0b7a5d1e-0000-4000-8000-000000000003"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("PAYLOAD_MISMATCH");
    // 异账号拒绝。
    assertThatThrownBy(() -> services.directoryService().replayIfPresent(
        v2Context, v2Id, fields, BOB, "0b7a5d1e-0000-4000-8000-000000000003"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("首次创建账号不一致");
    // 完整C不一致拒绝。
    FormVersionContext otherResource = FormV6TestSupport.contextFor(
        jdbc, services.objectMapper(), services.contextService(), scopeId, v2Id, 15, 502);
    assertThatThrownBy(() -> services.directoryService().replayIfPresent(
        otherResource, v2Id, fields, ALICE, "0b7a5d1e-0000-4000-8000-000000000003"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("完整资源与目录证据不一致");
    // 作废记录拒绝重放。
    jdbc.update("update " + v2.recordTable() + " set deleted = true");
    assertThatThrownBy(() -> services.directoryService().replayIfPresent(
        v2Context, v2Id, fields, ALICE, "0b7a5d1e-0000-4000-8000-000000000003"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("已作废");
  }

  private static JsonNode json(String value) {
    return JsonNodeFactory.instance.textNode(value);
  }

  private static JsonNode jsonInt(int value) {
    return JsonNodeFactory.instance.numberNode(value);
  }

  private FormV6TestSupport.WiredServices wired() {
    JdbcTemplate jdbc = FormV6TestSupport.migrate(POSTGRES);
    return FormV6TestSupport.wire(jdbc);
  }

  private static void insertLegacyRows(
      JdbcTemplate jdbc, long firstId, long secondId, long projectId) {
    jdbc.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (?, 'http://gitlab.n1.local', ?, ?, 'merge_request', ?, 'code_review',
           'Alice', 30, 1, 2, 3, 4, 5, 'legacy', false, current_timestamp, current_timestamp)
        """,
        firstId, projectId, firstId, Long.toString(firstId));
    jdbc.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (?, 'http://gitlab.n1.local', ?, ?, 'merge_request', ?, 'code_review',
           'Bob', 45, 6, 7, 8, 9, 10, 'second', false, current_timestamp, current_timestamp)
        """,
        secondId, projectId, secondId, Long.toString(secondId));
  }

  private static long versionId(JdbcTemplate jdbc, long scopeId, String recordTable) {
    return jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ? and record_table = ?",
        Long.class, scopeId, recordTable);
  }

  private static String fingerprint(
      FormV6TestSupport.WiredServices services,
      long scopeId,
      long versionId,
      long projectId,
      long resourceId,
      FormActorContext actor,
      String score,
      String note) {
    FormVersionContext context = FormV6TestSupport.contextFor(
        services.jdbc(), services.objectMapper(), services.contextService(),
        scopeId, versionId, projectId, resourceId);
    Map<String, JsonNode> fields = note == null
        ? Map.of("score", jsonInt(Integer.parseInt(score)))
        : Map.of("score", jsonInt(Integer.parseInt(score)), "note", json(note));
    var canonical = services.fieldValueService().validateForReplay(context, fields);
    return services.fieldValueService().fingerprint(context, actor.userId(),
        canonical.canonicalFields());
  }

  private static ValidatedFormValues values(
      FormV6TestSupport.WiredServices services,
      FormVersionContext context,
      Map<String, JsonNode> fields) {
    return services.fieldValueService().validate(context, fields);
  }
}
