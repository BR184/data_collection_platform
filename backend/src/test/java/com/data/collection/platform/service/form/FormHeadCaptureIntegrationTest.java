package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
    services = FormV6TestSupport.wire(jdbc);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("delete from form_publication_captures");
    jdbc.update("delete from form_head_captures");
    jdbc.update("delete from form_directory_events");
    jdbc.update("delete from form_directory_event_cursors");
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

    assertThat(cursor.consumePending(10)).isEqualTo(1);

    assertThat(((Number) head("latest_change_version")).longValue()).isEqualTo(2);
    assertThat(head("status")).isEqualTo("PENDING");
    assertThat(((Number) head("consumed_change_version")).longValue()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select last_consumed_event_id from form_directory_event_cursors where scope_id = ?",
        Long.class, scopeId)).isEqualTo(eventId);
    assertThat(jdbc.queryForObject(
        "select status from form_directory_events where id = ?", String.class, eventId))
        .isEqualTo("CONSUMED");

    assertThat(cursor.consumePending(10)).as("重复执行不得重复唤醒").isZero();
    assertThat(((Number) head("latest_change_version")).longValue()).isEqualTo(2);
  }

  private Object head(String column) {
    return jdbc.queryForMap("select " + column + " from form_change_heads where id = ?", headId)
        .get(column);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now();
  }
}
