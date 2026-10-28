package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
  void shouldExposeRegisteredLegacyRecordReadOnlyAndRejectWrites() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n2-legacy-readonly", configId, URL, 41, "DRAFT");
    FormV6TestSupport.insertLegacyPair(jdbc, 941001, 941002, URL, 41);
    jdbc.execute(
        "create table if not exists ods_gitlab_merge_requests("
            + "id bigint primary key, target_project_id bigint, iid bigint,"
            + " mirror_deleted boolean default false)");
    jdbc.execute(
        "create table if not exists ods_gitlab_projects("
            + "id bigint primary key, path varchar(255), namespace_id bigint,"
            + " mirror_deleted boolean default false)");
    jdbc.execute(
        "create table if not exists ods_gitlab_namespaces("
            + "id bigint primary key, path varchar(255), mirror_deleted boolean default false)");
    jdbc.update(
        "insert into ods_gitlab_merge_requests(id, target_project_id, iid, mirror_deleted)"
            + " values (941041, 41, 941001, false)");
    jdbc.update(
        "insert into ods_gitlab_projects(id, path, namespace_id, mirror_deleted)"
            + " values (41, 'legacy-project', 7, false)");
    jdbc.update(
        "insert into ods_gitlab_namespaces(id, path, mirror_deleted) values (7, 'team', false)");
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    FormV6RegistrationService.RegistrationResult registered = services.tx().execute(status ->
        services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-N2", precheck.count(), precheck.rowHash()));
    // 原位 LEGACY/NONE 范围不进入 ACTIVE 写指针（等待 N3 正式迁移），退役后只读展示历史。
    jdbc.update("update form_template_scopes set status = 'RETIRED' where id = ?", scopeId);

    FormActorContext actor =
        new FormActorContext(
            "alice",
            "Alice",
            java.util.Set.of(
                PlatformPermissionCodes.FORM_RECORD_CREATE,
                PlatformPermissionCodes.FORM_RECORD_EDIT_OWN,
                PlatformPermissionCodes.FORM_RECORD_DELETE_OWN));
    FormDetailResponse detail =
        services.recordService().detail(
            "code_review", URL, 41L, "merge_request", "941001", actor);

    assertThat(detail.writable()).isFalse();
    assertThat(detail.currentVersionId()).isEqualTo(detail.storageVersionId());
    assertThat(detail.versionSpecs()).containsKey(detail.currentVersionId());
    assertThat(detail.records()).singleElement().satisfies(record -> {
      assertThat(record.id()).isEqualTo("941001");
      assertThat(record.storageVersionId()).isEqualTo(detail.currentVersionId());
      assertThat(record.originVersionId()).isEqualTo(detail.currentVersionId());
      assertThat(record.canEdit()).isFalse();
      assertThat(record.canVoid()).isFalse();
      assertThat(record.fields()).containsEntry("reviewerUserId", "Alice");
    });

    // 原位历史版本没有实例表与创建人证据：即使拥有权限也不能写入。
    assertThatThrownBy(
            () ->
                services.recordService().update(
                    "941001",
                    new FormUpdateRequest(
                        URL,
                        41L,
                        "merge_request",
                        "941001",
                        "code_review",
                        detail.scopeId(),
                        detail.storageVersionId(),
                        detail.storageVersionId(),
                        1,
                        Map.of("reviewerUserId", JsonNodeFactory.instance.textNode("Alice"))),
                    actor,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("原位历史记录");
    assertThat(jdbc.queryForObject(
        "select reviewer from collect_form_records where id = 941001", String.class))
        .isEqualTo("Alice");
    assertThat(registered.directoryRows()).isEqualTo(2);
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
    // request_key 存在但首次指纹缺失：正式结构允许该形状（无账号），登记仍按证据不完整 409。
    long instanceId = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, 14, 401);
    FormV6TestSupport.insertRecord(
        jdbc, v2.recordTable(), instanceId, v2Id, null,
        "0b7a5d1e-0000-4000-8000-000000000002", null, 1, false, "5", null);
    assertThatThrownBy(() -> services.registrationService().precheckCopiedRecords(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("首次指纹或账号证据缺失");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                scopeId, "operator", 1, "0")))
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
            + "(id, instance_id, origin_version_id, record_version, score,"
            + " created_at, updated_at)"
            + " values (502, ?, ?, 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        staleInstance, v1Id);
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

  // ===== N1-R01：登记证据必须覆盖全值/修订/作废轴 =====

  @Test
  void shouldRecheckFullValueEvidenceAfterCarrierValueRevisionOrVoidChange() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(services, 21, 701, "0b7a5d1e-0000-4000-8000-0000000000a1");
    String carrierTable = fixture.v2().recordTable();
    long recordId = fixture.recordId();

    String baseline = services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    // 业务值变化：预检证据必须改变，携带旧证据的登记整体拒绝。
    jdbc.update("update " + carrierTable + " set score = 99 where id = ?", recordId);
    assertThat(services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash())
        .as("承载业务值变化后预检指纹必须改变")
        .isNotEqualTo(baseline);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, baseline)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");
    String afterValue =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    // 修订与更新时间轴：值不变也必须被证据捕获。
    jdbc.update(
        "update " + carrierTable
            + " set record_version = record_version + 1, updated_at = updated_at + interval '1 hour'"
            + " where id = ?",
        recordId);
    assertThat(services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash())
        .as("修订号/更新时间变化后预检指纹必须改变")
        .isNotEqualTo(afterValue);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, afterValue)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");
    String afterRevision =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    // 作废轴。
    jdbc.update("update " + carrierTable + " set deleted = true where id = ?", recordId);
    assertThat(services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash())
        .as("作废状态变化后预检指纹必须改变")
        .isNotEqualTo(afterRevision);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, afterRevision)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class,
        fixture.scopeId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class,
        fixture.scopeId())).isZero();
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        fixture.scopeId())).isZero();
  }

  @Test
  void shouldRejectIncompleteOrMismatchedDirectoryInsteadOfReportingAlreadyRegistered() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-partial", configId, URL, 23, "DRAFT");
    insertLegacyRows(jdbc, 231, 232, 23);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(scopeId);
    assertThat(services.tx().execute(status -> services.registrationService().registerLegacyDemo(
            scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()))
        .status()).isEqualTo("REGISTERED");
    int revision = jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class, scopeId);
    long versions = jdbc.queryForObject(
        "select count(*) from form_template_versions where scope_id = ?", Long.class, scopeId);

    // 同数量但身份不符：目录 project_id 被改错，仍必须按证据不一致拒绝，不报已完成。
    jdbc.update("update form_record_locations set project_id = 999 where record_id = 231");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("证据不一致");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerLegacyDemo(
                scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash())))
        .isInstanceOf(FormConflictException.class);
    // 部分登记：目录少一行不得被当作已完成。
    jdbc.update("update form_record_locations set project_id = 23 where record_id = 231");
    jdbc.update("delete from form_record_locations where record_id = 232");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记不完整");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerLegacyDemo(
                scopeId, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash())))
        .isInstanceOf(FormConflictException.class);
    // 失败零副作用：不自动补认历史、不新增版本、不推进修订。
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_template_versions where scope_id = ?", Integer.class, scopeId))
        .isEqualTo(versions);
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class, scopeId))
        .isEqualTo(revision);
  }

  // ===== N1-R02：范围归属判定 =====

  @Test
  void shouldRegisterDisjointProjectScopesIndependentlyAndRejectTrueOverlap() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeA = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-disjoint-a", configId, URL, 11, "DRAFT");
    long scopeB = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-disjoint-b", configId, URL, 12, "DRAFT");
    FormV6TestSupport.insertLegacyRow(
        jdbc, 900001, URL, 11, 900001, "Alice", 30, 1, 2, 3, 4, 5, "legacy", false);
    FormV6TestSupport.insertLegacyRow(
        jdbc, 900002, URL, 12, 900002, "Bob", 45, 6, 7, 8, 9, 10, "second", false);

    // 只属于其它范围的行不进入本次选集，也不构成冲突。
    FormV6RegistrationService.PrecheckReport precheckA =
        services.registrationService().precheckLegacyDemo(scopeA);
    assertThat(precheckA.count()).isEqualTo(1);
    assertThat(services.tx().execute(status -> services.registrationService().registerLegacyDemo(
            scopeA, "operator", "LEGACY-DEMO", precheckA.count(), precheckA.rowHash()))
        .status()).isEqualTo("REGISTERED");
    assertThat(FormV6TestSupport.directoryRows(jdbc, scopeA))
        .extracting(row -> row.get("record_id")).containsExactly(900001L);

    FormV6RegistrationService.PrecheckReport precheckB =
        services.registrationService().precheckLegacyDemo(scopeB);
    assertThat(precheckB.count()).isEqualTo(1);
    assertThat(services.tx().execute(status -> services.registrationService().registerLegacyDemo(
            scopeB, "operator", "LEGACY-DEMO", precheckB.count(), precheckB.rowHash()))
        .status()).isEqualTo("REGISTERED");
    assertThat(FormV6TestSupport.directoryRows(jdbc, scopeB))
        .extracting(row -> row.get("record_id")).containsExactly(900002L);

    // 真正重叠：同时绑定 11 与 12 的范围必须按重复归属整体拒绝。
    FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-overlap", configId, URL, List.of(11L, 12L), 11, "DRAFT");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(scopeA))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("重复归属");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations", Integer.class)).isEqualTo(2);
  }

  @Test
  void shouldRejectCarrierWhenInstanceEvidenceCrossesScopeBinding() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-cross-c", configId, URL, 71, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, scopeId, v1.versionId());
    // 实例跨项目：范围绑定 71，实例落在 72。
    long foreignProjectInstance = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, 72, 801);
    FormV6TestSupport.insertRecord(
        jdbc, v1.recordTable(), foreignProjectInstance, v1.versionId(), null, null, null,
        1, false, "4", null);
    assertThatThrownBy(() -> services.registrationService().precheckCopiedRecords(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("范围绑定");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(scopeId, "operator", 1, "0")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("范围绑定");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isZero();
    // 同资源 ID、跨来源：来源实例与地址都不属于范围绑定。
    jdbc.update("delete from " + v1.recordTable());
    jdbc.update("delete from " + v1.instanceTable());
    long foreignSourceInstance = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, "another-source", "http://other.invalid",
        71, 802);
    FormV6TestSupport.insertRecord(
        jdbc, v1.recordTable(), foreignSourceInstance, v1.versionId(), null, null, null,
        1, false, "5", null);
    assertThatThrownBy(() -> services.registrationService().precheckCopiedRecords(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("范围绑定");
    // 来源不得由范围伪造：目录仍为空即未写入任何来源。
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isZero();
  }

  // ===== N1-R04：结构准入 =====

  @Test
  void shouldRejectRegistrationWhenFormalStructureVerifierReportsDrift() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(services, 22, 711, "0b7a5d1e-0000-4000-8000-0000000000b2");
    // 夹具先是真结构：正式核验器通过。
    FormVersionSchema v2Schema = FormV6TestSupport.formalSchema(
        jdbc, fixture.scopeId(), fixture.v2().versionId(), "code_review");
    assertThatCode(() -> new FormVersionStructureVerifier(jdbc).verify(v2Schema))
        .doesNotThrowAnyException();
    // 破坏承载表列类型：正式核验器报漂移，登记不得放行。
    jdbc.update(
        "alter table " + fixture.v2().recordTable() + " alter column score type text using score::text");
    assertThatThrownBy(() -> new FormVersionStructureVerifier(jdbc).verify(v2Schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("结构漂移");
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(fixture.scopeId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构漂移");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, "0")))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构漂移");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class,
        fixture.scopeId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from form_template_versions where scope_id = ?"
            + " and record_source_role = 'LEGACY_SNAPSHOT'",
        Integer.class, fixture.scopeId())).isZero();

    // 路径 A 同样核验旧物理表契约：旧列类型与冻结规格不一致即拒绝。
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-legacy-contract", configId, URL, 24, "DRAFT");
    insertLegacyRows(jdbc, 241, 242, 24);
    jdbc.update("alter table collect_form_records alter column reviewer type varchar(64)");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("物理列契约");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_template_versions where scope_id = ?", Integer.class, scopeId))
        .isZero();
  }

  // ===== 复验第二轮：N1-R01 全值与持久完成证据 =====

  @Test
  void shouldDistinguishNullFromEmptyStringInCarrierEvidence() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixtureWithNote(
        services, 25, 751, "0b7a5d1e-0000-4000-8000-0000000000d1");
    String carrierTable = fixture.v2().recordTable();
    long recordId = fixture.recordId();
    assertThat(jdbc.queryForObject(
        "select note_text from " + carrierTable + " where id = ?", String.class, recordId)).isNull();

    String nullFingerprint =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    jdbc.update("update " + carrierTable + " set note_text = '' where id = ?", recordId);
    String emptyFingerprint =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    assertThat(emptyFingerprint)
        .as("NULL 与空串必须是不同的登记证据")
        .isNotEqualTo(nullFingerprint);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, nullFingerprint)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");
    assertThat(services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, emptyFingerprint)).status())
        .isEqualTo("REGISTERED");

    // 登记后把承载行改回 NULL：持久完成证据不符，必须 409 而不是宣称已登记。
    jdbc.update("update " + carrierTable + " set note_text = null where id = ?", recordId);
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(fixture.scopeId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记后源证据已变化");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class,
        fixture.scopeId())).isEqualTo(1);
  }

  @Test
  void shouldDetectChangesInNonCarrierCopies() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 27, 761, "0b7a5d1e-0000-4000-8000-0000000000d2");
    String originTable = fixture.v1().recordTable();
    String originInstanceTable = fixture.v1().instanceTable();
    long recordId = fixture.recordId();
    String baseline =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();

    // 只改非承载旧副本的业务值。
    jdbc.update("update " + originTable + " set score = 8 where id = ?", recordId);
    String afterValue =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    assertThat(afterValue)
        .as("非承载副本业务值变化必须进入登记证据")
        .isNotEqualTo(baseline);
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, baseline)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");

    // 只改非承载旧副本的时间轴。
    jdbc.update("update " + originTable
        + " set updated_at = updated_at - interval '1 hour' where id = ?", recordId);
    String afterTime =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    assertThat(afterTime).isNotEqualTo(afterValue);

    // 只改非承载旧副本的实例身份。
    jdbc.update("update " + originInstanceTable + " set project_id = 29"
        + " where id = (select instance_id from " + originTable + " where id = ?)", recordId);
    String afterIdentity =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    assertThat(afterIdentity).isNotEqualTo(afterTime);

    // 非承载副本修订与承载相同但业务值不同：按既有歧义规则明确拒绝。
    jdbc.update("update " + originTable + " set record_version = 2 where id = ?", recordId);
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(fixture.scopeId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("record_version相同但业务值不同");

    // 还原为非承载旧副本后，当前证据可正常登记且承载仍是当前版本。
    jdbc.update("update " + originTable
        + " set record_version = 1, score = 3 where id = ?", recordId);
    jdbc.update("update " + originInstanceTable + " set project_id = 27"
        + " where id = (select instance_id from " + originTable + " where id = ?)", recordId);
    String restored =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    FormV6RegistrationService.RegistrationResult result = services.tx().execute(status ->
        services.registrationService().registerCopiedRecords(
            fixture.scopeId(), "operator", 1, restored));
    assertThat(result.status()).isEqualTo("REGISTERED");
    assertThat(result.versionId()).isEqualTo(fixture.v2().versionId());
  }

  @Test
  void shouldRejectReentryWhenSourceChangedAfterRegistration() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 28, 771, "0b7a5d1e-0000-4000-8000-0000000000d3");
    String carrierTable = fixture.v2().recordTable();
    long recordId = fixture.recordId();
    String registered =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    assertThat(services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, registered)).status())
        .isEqualTo("REGISTERED");
    int revision = jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        fixture.scopeId());

    // 登记后编辑承载行：预检与重入都必须比对持久完成证据并 409。
    jdbc.update("update " + carrierTable
        + " set score = 99, record_version = record_version + 1,"
        + " updated_at = updated_at + interval '1 hour' where id = ?", recordId);
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(fixture.scopeId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记后源证据已变化");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                fixture.scopeId(), "operator", 1, registered)))
        .isInstanceOf(FormConflictException.class);
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        fixture.scopeId())).isEqualTo(revision);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class,
        fixture.scopeId())).isEqualTo(1);

    // 完成证据缺失同样明确 409，不能把当前源哈希当成历史登记证据。
    jdbc.update("delete from form_directory_events where scope_id = ?", fixture.scopeId());
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(fixture.scopeId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记完成证据缺失");

    // 旧 Demo 路径同样比对持久完成证据。
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long legacyScope = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-completion-legacy", configId, URL, 30, "DRAFT");
    FormV6TestSupport.insertLegacyRow(
        jdbc, 970001, URL, 30, 970001, "Alice", 30, 1, 2, 3, 4, 5, "legacy", false);
    FormV6RegistrationService.PrecheckReport precheck =
        services.registrationService().precheckLegacyDemo(legacyScope);
    services.tx().execute(status -> services.registrationService().registerLegacyDemo(
        legacyScope, "operator", "LEGACY-DEMO", precheck.count(), precheck.rowHash()));
    jdbc.update("update collect_form_records set remark = 'edited' where id = 970001");
    assertThatThrownBy(() -> services.registrationService().precheckLegacyDemo(legacyScope))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记后源证据已变化");
  }

  // ===== 复验第二轮：N1-R02 范围绑定合法性 =====

  @Test
  void shouldRejectCopiedRegistrationWhenActiveScopeBindingHashIsCorrupted() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 61, 801, "0b7a5d1e-0000-4000-8000-0000000000e1");
    try {
      jdbc.update("update form_template_scopes set binding_hash = repeat('0', 64) where id = ?",
          fixture.scopeId());

      assertThatThrownBy(() ->
              services.registrationService().precheckCopiedRecords(fixture.scopeId()))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID");
      assertThatThrownBy(() -> services.tx().execute(status ->
              services.registrationService().registerCopiedRecords(
                  fixture.scopeId(), "operator", 1, "0")))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID");
      assertThat(jdbc.queryForObject(
          "select count(*) from form_record_locations where scope_id = ?", Integer.class,
          fixture.scopeId())).isZero();
      assertThat(jdbc.queryForObject(
          "select count(*) from form_directory_events where scope_id = ?", Integer.class,
          fixture.scopeId())).isZero();
    } finally {
      // 共享测试库内归还绑定损坏的 ACTIVE 范围，避免污染后续用例的全局启动复核。
      retireScope(jdbc, fixture.scopeId());
    }
  }

  @Test
  void shouldRejectLegacyRegistrationWhenVersionProjectIsOutsideScopeProjects() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    // 绑定哈希按真实绑定推导，但版本项目 63 不在项目集合 [62] 内：语义非法。
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-bind-project", configId, URL, List.of(62L), 63, "DRAFT");
    FormV6TestSupport.FixtureTables version = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, scopeId, version.versionId());
    try {
      assertThatThrownBy(() ->
              services.registrationService().precheckCopiedRecords(scopeId))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("发布项目");
      assertThatThrownBy(() -> services.tx().execute(status ->
              services.registrationService().registerCopiedRecords(
                  scopeId, "operator", 1, "0")))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("发布项目");
      assertThat(jdbc.queryForObject(
          "select count(*) from form_record_locations where scope_id = ?", Integer.class,
          scopeId)).isZero();
      assertThat(jdbc.queryForObject(
          "select count(*) from form_directory_events where scope_id = ?", Integer.class,
          scopeId)).isZero();
    } finally {
      retireScope(jdbc, scopeId);
    }
  }

  @Test
  void shouldRejectRegistrationWhenScopeSourceConfigOrInstanceMismatches() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 68, 821, "0b7a5d1e-0000-4000-8000-0000000000e3");
    try {
      // 来源配置已被改址：范围保存的绑定不再对应当前来源。
      jdbc.update("update gitlab_sync_configs set web_base_url = ? where id = ?",
          "http://gitlab.other.local", fixture.configId());
      assertThatThrownBy(() ->
              services.registrationService().precheckCopiedRecords(fixture.scopeId()))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("Web 基址");
      assertThatThrownBy(() -> services.tx().execute(status ->
              services.registrationService().registerCopiedRecords(
                  fixture.scopeId(), "operator", 1, "0")))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID");

      // 来源实例与绑定不一致。
      jdbc.update("update gitlab_sync_configs set web_base_url = ? where id = ?",
          URL, fixture.configId());
      jdbc.update("update form_template_scopes set source_instance = 'other' where id = ?",
          fixture.scopeId());
      assertThatThrownBy(() ->
              services.registrationService().precheckCopiedRecords(fixture.scopeId()))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("来源实例");
      assertThat(jdbc.queryForObject(
          "select count(*) from form_record_locations where scope_id = ?", Integer.class,
          fixture.scopeId())).isZero();
    } finally {
      jdbc.update("update gitlab_sync_configs set web_base_url = ? where id = ?",
          URL, fixture.configId());
      jdbc.update("update form_template_scopes set source_instance = 'default' where id = ?",
          fixture.scopeId());
      retireScope(jdbc, fixture.scopeId());
    }
  }

  @Test
  void shouldRejectRegistrationWhenScopePointerBelongsToAnotherScope() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 64, 831, "0b7a5d1e-0000-4000-8000-0000000000e4");
    long ownerScope = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-pointer-owner", fixture.configId(), URL, 65, "DRAFT");
    FormV6TestSupport.FixtureTables ownerVersion = FormV6TestSupport.createRecordVersion(
        jdbc, ownerScope, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    // 数据库组合外键阻止指针跨范围；这里在隔离库临时解除该约束，模拟历史遗留的指针错挂状态，
    // 验证服务层复核不会信任可疑指针直接登记。
    jdbc.execute("alter table form_template_scopes drop constraint form_scope_current_version_fk");
    try {
      jdbc.update("update form_template_scopes set current_version_id = ? where id = ?",
          ownerVersion.versionId(), fixture.scopeId());
      assertThatThrownBy(() ->
              services.registrationService().precheckCopiedRecords(fixture.scopeId()))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("当前指针");
      assertThatThrownBy(() -> services.tx().execute(status ->
              services.registrationService().registerCopiedRecords(
                  fixture.scopeId(), "operator", 1, "0")))
          .isInstanceOf(FormConflictException.class)
          .hasMessageContaining("SCOPE_BINDING_INVALID")
          .hasMessageContaining("当前指针");
      assertThat(jdbc.queryForObject(
          "select count(*) from form_record_locations where scope_id = ?", Integer.class,
          fixture.scopeId())).isZero();
      assertThat(jdbc.queryForObject(
          "select count(*) from form_directory_events where scope_id = ?", Integer.class,
          fixture.scopeId())).isZero();
    } finally {
      jdbc.update("update form_template_scopes set current_version_id = ? where id = ?",
          fixture.v2().versionId(), fixture.scopeId());
      retireScope(jdbc, fixture.scopeId());
      jdbc.execute("alter table form_template_scopes add constraint form_scope_current_version_fk"
          + " foreign key (id, current_version_id)"
          + " references form_template_versions(scope_id, id)");
    }
  }

  @Test
  void shouldRejectWhenBindingChangesWhileRegistrationWaitsForScopeLock() throws Exception {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    CarrierFixture fixture = carrierFixture(
        services, 66, 811, "0b7a5d1e-0000-4000-8000-0000000000e2");
    String fingerprint =
        services.registrationService().precheckCopiedRecords(fixture.scopeId()).rowHash();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection locker = jdbc.getDataSource().getConnection()) {
      locker.setAutoCommit(false);
      try (Statement statement = locker.createStatement()) {
        statement.execute("select id from form_template_scopes where id = "
            + fixture.scopeId() + " for update");
      }
      Future<Throwable> pending = pool.submit(() -> {
        try {
          services.tx().execute(status -> services.registrationService().registerCopiedRecords(
              fixture.scopeId(), "operator", 1, fingerprint));
          return null;
        } catch (Throwable error) {
          return error;
        }
      });
      awaitScopeLockWait(jdbc);
      try (Statement statement = locker.createStatement()) {
        statement.execute("update form_template_scopes set binding_hash = repeat('0', 64)"
            + " where id = " + fixture.scopeId());
      }
      locker.commit();
      Throwable failure = pending.get(60, TimeUnit.SECONDS);
      assertThat(failure).isInstanceOf(FormConflictException.class);
      assertThat(failure.getMessage()).contains("SCOPE_BINDING_INVALID");
    } finally {
      pool.shutdownNow();
      retireScope(jdbc, fixture.scopeId());
    }
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class,
        fixture.scopeId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class,
        fixture.scopeId())).isZero();
  }

  /** 共享测试库的损坏范围不留在 ACTIVE，避免影响同类用例的全局绑定复核。 */
  private static void retireScope(JdbcTemplate jdbc, long scopeId) {
    jdbc.update("update form_template_scopes set status = 'RETIRED' where id = ?", scopeId);
  }

  /** 等待登记线程真正进入范围行锁等待，避免用固定 sleep 猜时序。 */
  private static void awaitScopeLockWait(JdbcTemplate jdbc) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      Integer waiting = jdbc.queryForObject(
          "select count(*) from pg_stat_activity where datname = current_database()"
              + " and wait_event_type = 'Lock'"
              + " and query like '%form_template_scopes%for update%'",
          Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("登记线程未在超时内进入范围行锁等待");
  }

  // ===== 复验第三轮：N1-R01 真实JDBC数组证据 =====

  @Test
  void shouldDistinguishJdbcArrayBoundariesInRegistrationEvidence() {
    FormV6TestSupport.WiredServices services = wired();
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-array", configId, URL, 29, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_ARRAY, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", FormV6TestSupport.SPEC_ARRAY, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, scopeId, v2.versionId());
    insertArrayCarrier(jdbc, scopeId, configId, v1, v2, 29, 7001, "a, b");

    String registered =
        services.registrationService().precheckCopiedRecords(scopeId).rowHash();
    assertThat(services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                scopeId, "operator", 1, registered)).status())
        .isEqualTo("REGISTERED");
    int revision = jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        scopeId);

    // 单个元素 "a, b" 与两个元素 "a"/"b" 均为合法选项，但属于不同源值。
    jdbc.update("update " + v1.recordTable()
        + " set tags = array['a', 'b']::text[] where id = 7001");
    assertThatThrownBy(() ->
            services.registrationService().precheckCopiedRecords(scopeId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("登记后源证据已变化");
    // 携带旧预检重入同样 409：当前源哈希已不同于登记时保存的证据。
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                scopeId, "operator", 1, registered)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源已变化");
    // 失败零副作用：目录、事件、集合修订与承载行保持不变。
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class, scopeId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class, scopeId))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class,
        scopeId)).isEqualTo(revision);
    assertThat(jdbc.queryForObject(
        "select tags::text from " + v2.recordTable() + " where id = 7001", String.class))
        .isEqualTo("{\"a, b\"}");

    // 新范围：预检后数组元素边界变化，旧预检必须失效且登记零写入。
    long driftScope = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-array-drift", configId, URL, 30, "DRAFT");
    FormV6TestSupport.FixtureTables driftV1 = FormV6TestSupport.createRecordVersion(
        jdbc, driftScope, "code_review", "V1", FormV6TestSupport.SPEC_ARRAY, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables driftV2 = FormV6TestSupport.createRecordVersion(
        jdbc, driftScope, "code_review", "V2", FormV6TestSupport.SPEC_ARRAY, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, driftScope, driftV2.versionId());
    insertArrayCarrier(jdbc, driftScope, configId, driftV1, driftV2, 30, 7101, "a, b");
    String beforeDrift =
        services.registrationService().precheckCopiedRecords(driftScope).rowHash();
    jdbc.update("update " + driftV1.recordTable()
        + " set tags = array['a', 'b']::text[] where id = 7101");
    assertThatThrownBy(() -> services.tx().execute(status ->
            services.registrationService().registerCopiedRecords(
                driftScope, "operator", 1, beforeDrift)))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("precheck与register之间源已变化");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Integer.class,
        driftScope)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?", Integer.class,
        driftScope)).isZero();
  }

  /** 建 v1 原始副本与 v2 承载副本，tags 使用真实 text[] 数组值。 */
  private static void insertArrayCarrier(
      JdbcTemplate jdbc,
      long scopeId,
      long configId,
      FormV6TestSupport.FixtureTables v1,
      FormV6TestSupport.FixtureTables v2,
      long projectId,
      long recordId,
      String... tags) {
    long originInstance = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, projectId, recordId);
    String arrayLiteral = "array[" + java.util.Arrays.stream(tags)
        .map(tag -> "'" + tag.replace("'", "''") + "'")
        .collect(java.util.stream.Collectors.joining(", ")) + "]::text[]";
    jdbc.update("insert into " + v1.recordTable()
            + "(id, instance_id, origin_version_id, record_version, tags, created_at, updated_at)"
            + " values (?, ?, ?, 1, " + arrayLiteral + ", current_timestamp, current_timestamp)",
        recordId, originInstance, v1.versionId());
    long carrierInstance = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, projectId, recordId);
    jdbc.update("insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, record_version, tags, created_at, updated_at)"
            + " select id, ?, origin_version_id, record_version + 1, tags, created_at,"
            + " updated_at + interval '1 hour' from " + v1.recordTable() + " where id = ?",
        carrierInstance, recordId);
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
    FormV6TestSupport.insertLegacyPair(jdbc, firstId, secondId, URL, projectId);
  }

  private static long versionId(JdbcTemplate jdbc, long scopeId, String recordTable) {
    return jdbc.queryForObject(
        "select id from form_template_versions where scope_id = ? and record_table = ?",
        Long.class, scopeId, recordTable);
  }

  record CarrierFixture(
      long configId,
      long scopeId,
      FormV6TestSupport.FixtureTables v1,
      FormV6TestSupport.FixtureTables v2,
      long recordId,
      String firstHash) {}

  /**
   * 建 v1/v2 正式结构：v1 首次记录按 v1 冻结规格与版本身份计算首次指纹，再按迁移语义复制到
   * 当前版本 v2 承载（修订号递增、时间更新）。
   */
  private CarrierFixture carrierFixture(
      FormV6TestSupport.WiredServices services,
      long projectId,
      long resourceId,
      String requestKey) {
    return carrierFixture(
        services, projectId, resourceId, requestKey, FormV6TestSupport.SPEC_V2, false);
  }

  /** 承载版本保留 note 列（两版同为 SPEC_V1），用于 NULL/空串等文本证据用例。 */
  private CarrierFixture carrierFixtureWithNote(
      FormV6TestSupport.WiredServices services,
      long projectId,
      long resourceId,
      String requestKey) {
    return carrierFixture(
        services, projectId, resourceId, requestKey, FormV6TestSupport.SPEC_V1, true);
  }

  /**
   * 建 v1/v2 正式结构：v1 首次记录按 v1 冻结规格与版本身份计算首次指纹，再按迁移语义复制到
   * 当前版本 v2 承载（修订号递增、时间更新）。
   */
  private CarrierFixture carrierFixture(
      FormV6TestSupport.WiredServices services,
      long projectId,
      long resourceId,
      String requestKey,
      String carrierSpec,
      boolean copyNote) {
    JdbcTemplate jdbc = services.jdbc();
    long configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    long scopeId = FormV6TestSupport.insertScope(
        jdbc, "code_review", "n1-carrier-" + projectId, configId, URL, projectId, "DRAFT");
    FormV6TestSupport.FixtureTables v1 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V1", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    FormV6TestSupport.FixtureTables v2 = FormV6TestSupport.createRecordVersion(
        jdbc, scopeId, "code_review", "V2", carrierSpec, "PUBLISHED", true);
    FormV6TestSupport.activateScope(jdbc, scopeId, v2.versionId());
    String firstHash =
        fingerprint(services, scopeId, v1.versionId(), projectId, resourceId, ALICE, "3", "首个");
    long originInstance = FormV6TestSupport.insertInstance(
        jdbc, v1.instanceTable(), scopeId, configId, URL, projectId, resourceId);
    long recordId = FormV6TestSupport.insertRecord(
        jdbc, v1.recordTable(), originInstance, v1.versionId(), ALICE.userId(), requestKey,
        firstHash, 1, false, "3", null);
    long carrierInstance = FormV6TestSupport.insertInstance(
        jdbc, v2.instanceTable(), scopeId, configId, URL, projectId, resourceId);
    String noteColumn = copyNote ? ", note_text" : "";
    String noteSelect = copyNote ? ", note_text" : "";
    jdbc.update(
        "insert into " + v2.recordTable()
            + "(id, instance_id, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version, deleted, score" + noteColumn
            + ", created_at, updated_at)"
            + " select id, ?, origin_version_id, template_version, created_by, request_key,"
            + " create_payload_hash, record_version + 1, deleted, score" + noteSelect
            + ", created_at, updated_at + interval '1 hour' from " + v1.recordTable()
            + " where id = ?",
        carrierInstance, recordId);
    return new CarrierFixture(configId, scopeId, v1, v2, recordId, firstHash);
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
