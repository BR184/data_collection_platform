package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.service.FormDirectoryEventWorker;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** N4 P3 捕获基础：范围锁序、租约、pending 与集合漂移零公开零结算。 */
@Testcontainers(disabledWithoutDocker = true)
class FormHeadCaptureIntegrationTest {
  private static final String URL = "http://gitlab.test";
  private static final long PROJECT_ID = 325L;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  private static JdbcTemplate jdbc;
  private static FormV6TestSupport.WiredServices services;
  private FormHeadCaptureService captureService;
  private long scopeId;
  private long headId;
  private long configId;
  private long versionId;

  @BeforeAll
  static void migrate() {
    jdbc = FormV6TestSupport.migrate(POSTGRES);
    jdbc.execute(
        "create table if not exists ods_gitlab_merge_requests("
            + "id bigint primary key, target_project_id bigint, iid bigint,"
            + " updated_at timestamp with time zone)");
    jdbc.execute(
        "create table if not exists ods_gitlab_issues("
            + "id bigint primary key, project_id bigint, iid bigint)");
    services = FormV6TestSupport.wire(jdbc);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("delete from form_publication_captures");
    jdbc.update("delete from form_head_captures");
    jdbc.update("delete from form_record_locations");
    jdbc.update("delete from form_directory_events");
    jdbc.update("delete from form_directory_event_cursors");
    jdbc.update("delete from fact_change_heads");
    jdbc.execute("alter sequence fact_change_version_seq restart with 1");
    jdbc.update("delete from form_change_heads");
    jdbc.update("delete from form_resource_authorities");
    jdbc.update("update form_template_scopes set status = 'DRAFT', current_version_id = null");
    jdbc.update("delete from form_template_versions");
    jdbc.update("delete from form_template_scopes");
    configId = FormV6TestSupport.insertSourceConfig(jdbc, URL);
    scopeId =
        FormV6TestSupport.insertScope(
            jdbc, "code_review", "capture-scope", configId, URL, PROJECT_ID, "DRAFT");
    FormV6TestSupport.FixtureTables tables =
        FormV6TestSupport.createRecordVersion(
            jdbc,
            scopeId,
            "code_review",
            "v1",
            FormV6TestSupport.SPEC_V1,
            "PUBLISHED",
            true);
    versionId = tables.versionId();
    FormV6TestSupport.activateScope(jdbc, scopeId, versionId);
    jdbc.update(
        "update form_template_scopes set source_set_revision = 1,"
            + " source_set_hash = form_source_set_hash(id) where id = ?",
        scopeId);
    String hash =
        jdbc.queryForObject(
            "select source_set_hash from form_template_scopes where id = ?", String.class, scopeId);
    jdbc.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        values (?, 'default', ?, ?, 'merge_request', 9001, 'code_review', ?, 'FORM', 1,
                current_timestamp, current_timestamp, current_timestamp)
        """,
        configId,
        URL,
        PROJECT_ID,
        scopeId);
    headId =
        jdbc.queryForObject(
            """
            insert into form_change_heads(
                source_config_id, source_instance, canonical_gitlab_base_url, project_id,
                resource_type, resource_id, template_key, scope_id, latest_change_version,
                consumed_change_version, pending_record_id, pending_record_version,
                pending_storage_version_id, pending_source_set_revision, pending_source_set_hash,
                pending_authority_revision, status, fencing_version, created_at, updated_at)
            values (?, 'default', ?, ?, 'merge_request', 9001, 'code_review', ?, 1, 0, 11, 2, ?,
                    1, ?, 1, 'PENDING', 0, current_timestamp, current_timestamp)
            returning id
            """,
            Long.class,
            configId,
            URL,
            PROJECT_ID,
            scopeId,
            tables.versionId(),
            hash);
    captureService =
        new FormHeadCaptureService(jdbc, services.reader(), services.objectMapper());
  }

  @Test
  void captureThenSettleAdvancesConsumedAtomically() {
    FormHeadCaptureService.HeadRow claimed = captureService.claim("worker-a", now()).orElseThrow();
    FormHeadCaptureService.Capture capture =
        captureService.capture(scopeId, headId, "worker-a", claimed.fencingVersion(), now());
    AtomicInteger published = new AtomicInteger();

    captureService.settle(capture, published::incrementAndGet);

    assertThat(published).hasValue(1);
    assertThat(head("consumed_change_version")).isEqualTo(1L);
    assertThat(head("status")).isEqualTo("CONSUMED");
    assertThat(
            jdbc.queryForObject(
                "select status from form_head_captures where head_id = ?", String.class, headId))
        .isEqualTo("CONSUMED");
  }

  @Test
  void pendingDriftAfterCapturePublishesNothingAndDoesNotSettle() {
    FormHeadCaptureService.HeadRow claimed = captureService.claim("worker-a", now()).orElseThrow();
    FormHeadCaptureService.Capture capture =
        captureService.capture(scopeId, headId, "worker-a", claimed.fencingVersion(), now());
    jdbc.update("update form_change_heads set pending_record_id = 12 where id = ?", headId);
    AtomicInteger published = new AtomicInteger();

    assertThatThrownBy(() -> captureService.settle(capture, published::incrementAndGet))
        .isInstanceOf(FormConflictException.class);

    assertThat(published).hasValue(0);
    assertThat(head("consumed_change_version")).isEqualTo(0L);
    assertThat(head("status")).isEqualTo("LEASED");
  }

  @Test
  void sourceSetDriftAndExpiredLeaseBothFailClosed() {
    FormHeadCaptureService.HeadRow claimed = captureService.claim("worker-a", now()).orElseThrow();
    FormHeadCaptureService.Capture capture =
        captureService.capture(scopeId, headId, "worker-a", claimed.fencingVersion(), now());
    jdbc.update("update form_template_scopes set source_set_revision = 2 where id = ?", scopeId);
    AtomicInteger published = new AtomicInteger();
    assertThatThrownBy(() -> captureService.settle(capture, published::incrementAndGet))
        .isInstanceOf(FormConflictException.class);
    assertThat(published).hasValue(0);

    jdbc.update("update form_template_scopes set source_set_revision = 1 where id = ?", scopeId);
    jdbc.update(
        "update form_change_heads set lease_until = clock_timestamp() - interval '1 minute'"
            + " where id = ?",
        headId);
    assertThatThrownBy(() -> captureService.settle(capture, published::incrementAndGet))
        .isInstanceOf(FormConflictException.class);
    assertThat(published).hasValue(0);
    assertThat(head("consumed_change_version")).isEqualTo(0L);
  }

  @Test
  void directoryEventCursorWakesOnlyResourcesInPublishedVersion() {
    jdbc.update(
        "update form_change_heads set latest_change_version = 1, consumed_change_version = 1,"
            + " status = 'CONSUMED' where id = ?",
        headId);
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode,
            instance_id, source_config_id, source_instance, gitlab_base_url, project_id,
            resource_type, resource_id, origin_spec_hash, created_at)
        values (910001, ?, ?, ?, 'MATERIALIZED', 1, ?, 'default', ?, ?, 'merge_request',
                '9001', ?, now())
        """,
        scopeId, versionId, versionId, configId, URL, PROJECT_ID, "a".repeat(64));
    Long eventId = jdbc.queryForObject(
        "insert into form_directory_events(scope_id, event_type, source_set_revision, payload)"
            + " values (?, 'VERSION_ADDED', 1, ?::jsonb) returning id",
        Long.class, scopeId, "{\"versionId\":" + versionId + "}");
    FormDirectoryEventCursorService cursor =
        new FormDirectoryEventCursorService(jdbc, services.objectMapper());

    FormDirectoryEventCursorService.Consumption first = cursor.consumePending(10);
    assertThat(first.consumed()).isEqualTo(1);
    assertThat(first.woken())
        .extracting(FormDirectoryEventCursorService.WokenResource::resourceType)
        .containsExactly("merge_request");

    assertThat(((Number) head("latest_change_version")).longValue()).isEqualTo(2);
    assertThat(head("status")).isEqualTo("PENDING");
    assertThat(((Number) head("consumed_change_version")).longValue()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select last_consumed_event_id from form_directory_event_cursors where scope_id = ?",
        Long.class, scopeId)).isEqualTo(eventId);
    assertThat(jdbc.queryForObject(
        "select status from form_directory_events where id = ?", String.class, eventId))
        .isEqualTo("CONSUMED");

    assertThat(cursor.consumePending(10).consumed()).as("重复执行不得重复唤醒").isZero();
    assertThat(((Number) head("latest_change_version")).longValue()).isEqualTo(2);
  }

  @Test
  void directoryEventWorkerWakesFactHeadAndResumesFromPersistedCursor() {
    seedWokenMergeRequestHead();
    com.data.collection.platform.entity.GitlabSyncConfig config =
        new com.data.collection.platform.entity.GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance("default");
    com.data.collection.platform.service.GitlabConfigService configService =
        org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class);
    org.mockito.Mockito.when(configService.listConfigs()).thenReturn(List.of(config));
    org.mockito.Mockito.when(configService.isSourceEnabled(config)).thenReturn(true);
    com.data.collection.platform.service.sync.SyncRunSubmissionService submissionService =
        org.mockito.Mockito.mock(
            com.data.collection.platform.service.sync.SyncRunSubmissionService.class);
    com.data.collection.platform.service.FormDirectoryEventBridge bridge =
        new com.data.collection.platform.service.FormDirectoryEventBridge(
            jdbc, configService, submissionService);
    FormDirectoryEventWorker worker = newWorker(bridge);

    assertThat(worker.consumeOnce(10)).isEqualTo(1);
    assertThat(factHeadVersion()).isEqualTo(1L);
    org.mockito.Mockito.verify(submissionService)
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.eq(config),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.anyString());

    assertThat(worker.consumeOnce(10)).as("同一事件不重复推进").isZero();
    assertThat(factHeadVersion()).isEqualTo(1L);

    insertDirectoryEvent(2);
    FormDirectoryEventWorker restarted = newWorker(bridge);
    assertThat(restarted.consumeOnce(10)).as("新进程从持久游标继续").isEqualTo(1);
    assertThat(factHeadVersion()).isEqualTo(2L);
  }

  @Test
  void directoryEventWakeFailureRollsBackConsumptionAndCursor() {
    seedWokenMergeRequestHead();
    com.data.collection.platform.service.FormDirectoryEventBridge bridge =
        org.mockito.Mockito.mock(
            com.data.collection.platform.service.FormDirectoryEventBridge.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("唤醒失败"))
        .when(bridge)
        .register(org.mockito.ArgumentMatchers.anyList());

    assertThatThrownBy(() -> newWorker(bridge).consumeOnce(10))
        .isInstanceOf(IllegalStateException.class);

    long eventId =
        jdbc.queryForObject("select max(id) from form_directory_events", Long.class);
    assertThat(jdbc.queryForObject(
        "select status from form_directory_events where id = ?", String.class, eventId))
        .as("唤醒失败不得消费事件")
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject(
        "select count(*) from form_directory_event_cursors where scope_id = ?",
        Long.class, scopeId)).isZero();
    assertThat(((Number) head("latest_change_version")).longValue()).isEqualTo(1);
    assertThat(head("status")).isEqualTo("CONSUMED");
    assertThat(factHeadVersion()).isNull();
  }

  @Test
  void emptyPublishedVersionWakesOldOnlyAndNoInstanceFormResources() {
    jdbc.update(
        "update form_change_heads set latest_change_version = 1, consumed_change_version = 1,"
            + " status = 'CONSUMED' where id = ?",
        headId);
    insertAuthorityAndHead(9002L);
    FormV6TestSupport.FixtureTables v2 =
        FormV6TestSupport.createRecordVersion(
            jdbc, scopeId, "code_review", "v2-empty", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    jdbc.update(
        "update form_template_scopes set current_version_id = ?, source_set_revision = 2,"
            + " source_set_hash = form_source_set_hash(id) where id = ?",
        v2.versionId(),
        scopeId);
    jdbc.update(
        "insert into form_directory_events(scope_id, event_type, source_set_revision, payload)"
            + " values (?, 'VERSION_ADDED', 2, ?::jsonb)",
        scopeId,
        "{\"versionId\":" + v2.versionId() + "}");

    FormDirectoryEventCursorService.Consumption consumption =
        new FormDirectoryEventCursorService(jdbc, services.objectMapper()).consumePending(10);

    assertThat(consumption.woken())
        .extracting(FormDirectoryEventCursorService.WokenResource::resourceId)
        .containsExactlyInAnyOrder(9001L, 9002L);
    assertThat(
            jdbc.queryForObject(
                "select pending_storage_version_id from form_change_heads where resource_id = 9001",
                Long.class))
        .as("只有旧记录的 FORM 资源也要指向新版本")
        .isEqualTo(v2.versionId());
    assertThat(
            jdbc.queryForObject(
                "select latest_change_version from form_change_heads where resource_id = 9002",
                Long.class))
        .as("没有记录实例的 FORM 权威同样被唤醒")
        .isEqualTo(2L);
  }

  @Test
  void staleDirectoryEventDoesNotOverwriteNewerPendingSnapshot() {
    FormV6TestSupport.FixtureTables v2 =
        FormV6TestSupport.createRecordVersion(
            jdbc, scopeId, "code_review", "v2-pending", FormV6TestSupport.SPEC_V1, "PUBLISHED", true);
    String hash =
        jdbc.queryForObject(
            "select form_source_set_hash(id) from form_template_scopes where id = ?",
            String.class,
            scopeId);
    jdbc.update(
        "update form_template_scopes set current_version_id = ?, source_set_revision = 2,"
            + " source_set_hash = ? where id = ?",
        v2.versionId(),
        hash,
        scopeId);
    jdbc.update(
        """
        update form_change_heads
           set latest_change_version = 5, consumed_change_version = 1, status = 'PENDING',
               pending_storage_version_id = ?, pending_source_set_revision = 2,
               pending_source_set_hash = ?
         where id = ?
        """,
        v2.versionId(),
        hash,
        headId);
    jdbc.update(
        "insert into form_directory_events(scope_id, event_type, source_set_revision, payload)"
            + " values (?, 'VERSION_ADDED', 1, ?::jsonb)",
        scopeId,
        "{\"versionId\":" + versionId + "}");

    FormDirectoryEventCursorService.Consumption consumption =
        new FormDirectoryEventCursorService(jdbc, services.objectMapper()).consumePending(10);

    assertThat(consumption.woken()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select pending_storage_version_id from form_change_heads where id = ?",
                Long.class,
                headId))
        .isEqualTo(v2.versionId());
    assertThat(
            jdbc.queryForObject(
                "select latest_change_version from form_change_heads where id = ?",
                Long.class,
                headId))
        .isEqualTo(5L);
  }

  @Test
  void failedSubmissionIsRetriedFromPersistedFactHeadWithoutNewEvents() {
    seedWokenMergeRequestHead();
    com.data.collection.platform.entity.GitlabSyncConfig config =
        new com.data.collection.platform.entity.GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance("default");
    com.data.collection.platform.service.GitlabConfigService configService =
        org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class);
    org.mockito.Mockito.when(configService.listConfigs()).thenReturn(List.of(config));
    org.mockito.Mockito.when(configService.isSourceEnabled(config)).thenReturn(true);
    com.data.collection.platform.service.sync.SyncRunSubmissionService submissionService =
        org.mockito.Mockito.mock(
            com.data.collection.platform.service.sync.SyncRunSubmissionService.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("首次提交失败"))
        .doReturn(null)
        .when(submissionService)
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.eq(config),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.anyString());
    FormDirectoryEventWorker worker =
        newWorker(new com.data.collection.platform.service.FormDirectoryEventBridge(
            jdbc, configService, submissionService));

    assertThat(worker.consumeOnce(10)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from form_directory_events where status = 'CONSUMED'",
                Long.class))
        .isEqualTo(1);

    assertThat(worker.consumeOnce(10)).as("没有新事件仍按持久 fact head 补投").isZero();
    org.mockito.Mockito.verify(submissionService, org.mockito.Mockito.times(2))
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.eq(config),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.anyString());
  }

  private void insertAuthorityAndHead(long resourceId) {
    jdbc.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        values (?, 'default', ?, ?, 'merge_request', ?, 'code_review', ?, 'FORM', 1,
                current_timestamp, current_timestamp, current_timestamp)
        """,
        configId,
        URL,
        PROJECT_ID,
        resourceId,
        scopeId);
    jdbc.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, latest_change_version,
            consumed_change_version, pending_authority_revision, status, fencing_version,
            created_at, updated_at)
        values (?, 'default', ?, ?, 'merge_request', ?, 'code_review', ?, 1, 1, 1,
                'CONSUMED', 0, current_timestamp, current_timestamp)
        """,
        configId,
        URL,
        PROJECT_ID,
        resourceId,
        scopeId);
  }

  private void seedWokenMergeRequestHead() {
    jdbc.update(
        "update form_change_heads set latest_change_version = 1, consumed_change_version = 1,"
            + " status = 'CONSUMED' where id = ?",
        headId);
    jdbc.update(
        "insert into merge_request_fact(source_system, source_instance, project_id,"
            + " merge_request_id, merge_request_iid, title, deleted)"
            + " values ('GITLAB', 'default', ?, 9001, 9001, 'dir-event', false)"
            + " on conflict do nothing",
        PROJECT_ID);
    jdbc.update(
        "insert into ods_gitlab_merge_requests(id, target_project_id, iid)"
            + " values (9001, ?, 9001) on conflict (id) do update"
            + " set target_project_id = excluded.target_project_id, iid = excluded.iid",
        PROJECT_ID);
    jdbc.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode,
            instance_id, source_config_id, source_instance, gitlab_base_url, project_id,
            resource_type, resource_id, origin_spec_hash, created_at)
        values (910001, ?, ?, ?, 'MATERIALIZED', 1, ?, 'default', ?, ?, 'merge_request',
                '9001', ?, now())
        """,
        scopeId, versionId, versionId, configId, URL, PROJECT_ID, "a".repeat(64));
    insertDirectoryEvent(1);
  }

  private FormDirectoryEventWorker newWorker(
      com.data.collection.platform.service.FormDirectoryEventBridge bridge) {
    return new FormDirectoryEventWorker(
        new FormDirectoryEventCursorService(jdbc, services.objectMapper()),
        bridge,
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
  }

  private void insertDirectoryEvent(int revision) {
    Long versionIdForRevision =
        jdbc.queryForObject(
            "select id from form_template_versions where scope_id = ? order by id limit 1",
            Long.class,
            scopeId);
    jdbc.update(
        "insert into form_directory_events(scope_id, event_type, source_set_revision, payload)"
            + " values (?, 'CURRENT_CHANGED', ?, ?::jsonb)",
        scopeId,
        revision,
        "{\"toVersionId\":" + versionIdForRevision + "}");
  }

  private Long factHeadVersion() {
    List<Long> values =
        jdbc.queryForList(
            "select latest_change_version from fact_change_heads"
                + " where source_instance = 'default' and fact_type = 'MERGE_REQUEST' and root_id = 9001",
            Long.class);
    return values.isEmpty() ? null : values.getFirst();
  }

  private Object head(String column) {
    return jdbc.queryForMap("select " + column + " from form_change_heads where id = ?", headId)
        .get(column);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now();
  }
}
