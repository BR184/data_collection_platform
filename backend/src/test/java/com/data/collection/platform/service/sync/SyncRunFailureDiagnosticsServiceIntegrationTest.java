package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.common.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运行维度故障定位查询的真实 PostgreSQL 证据。
 *
 * <p>夹具覆盖四类定位项各自的真实列、事件表与版本无关的待处理列表来源，用于验证"库内按运行分区限量与计数、
 * 分页稳定、移交快照不丢原诊断、待处理列表包含无父运行事实任务"这些契约。
 */
class SyncRunFailureDiagnosticsServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncRunFailureDiagnosticsService service;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("sync_run_failure_diagnostics_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();
    service =
        new SyncRunFailureDiagnosticsService(
            jdbcTemplate,
            new JsonUtils(
                new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)));
    insertRun(1L, "sr_1", 7L, "default");
    insertRun(2L, "sr_2", 7L, "default");
  }

  @Test
  void summary_bounds_items_per_kind_and_counts_events_without_progress() {
    for (int index = 0; index < 7; index++) {
      insertTableTaskFailure(100L + index, 1L, "gitlab_issues", "SCAN", "扫描失败原因");
    }
    insertScopeFailure(200L, 1L);
    insertFactFailure(300L, 1L, "FAILED", "NONE");
    insertFactFailure(301L, 1L, "PAUSED", "REQUIRES_DECISION");
    insertProjectionTask(400L, 1L, "FAILED", "NONE");
    insertProjectionTask(401L, 1L, "FAILED", "REQUIRES_DECISION");
    for (int index = 0; index < 6; index++) {
      insertEvent(1L, "RUN_CANCELLATION_REQUESTED", "第 " + index + " 条相关事件");
    }
    insertEvent(1L, "FACT_BUILD_PROGRESS", "进度 1/3 批次");
    insertEvent(1L, "FACT_BUILD_PROGRESS", "进度 3/3 批次");

    Map<Long, SyncRunFailureDiagnosticsService.RunDiagnostics> summaries =
        service.summarizeRuns(List.of(1L, 2L));

    SyncRunFailureDiagnosticsService.RunDiagnostics first = summaries.get(1L);
    assertThat(first.diagnosticCount()).isEqualTo(12);
    // 失败项与人工待处理项可重叠：401 既失败又等待人工决定，两者不能相加当作任务总数。
    assertThat(first.failureCount()).isEqualTo(11);
    assertThat(first.manualAttentionCount()).isEqualTo(2);
    assertThat(first.hasAttention()).isTrue();
    assertThat(first.diagnostics()).hasSize(SyncRunFailureDiagnosticsService.SUMMARY_ITEM_LIMIT);
    assertThat(first.diagnostics())
        .allSatisfy(item -> assertThat(item.get("kind")).isEqualTo("TABLE_TASK"));
    assertThat(first.eventCount()).isEqualTo(6);
    assertThat(first.eventTrail()).hasSize(SyncRunFailureDiagnosticsService.SUMMARY_EVENT_LIMIT);
    assertThat(first.eventTrail())
        .allSatisfy(item -> assertThat(item.get("eventType")).isEqualTo("RUN_CANCELLATION_REQUESTED"));
    assertThat(first.latestProgressMessage()).isEqualTo("进度 3/3 批次");
    assertThat(first.latestProgressAt()).isNotNull();

    SyncRunFailureDiagnosticsService.RunDiagnostics second = summaries.get(2L);
    assertThat(second.diagnosticCount()).isZero();
    assertThat(second.hasAttention()).isFalse();
    assertThat(second.eventCount()).isZero();
  }

  @Test
  void details_page_keeps_stable_order_and_total_matches_count() {
    insertTableTaskFailure(11L, 1L, "gitlab_issues", "SCAN", "扫描失败原因");
    insertTableTaskFailure(12L, 1L, "gitlab_merge_requests", "RECONCILE", "对账失败原因");
    insertScopeFailure(21L, 1L);
    insertFactFailure(31L, 1L, "FAILED", "NONE");

    List<Map<String, Object>> firstPage = service.diagnostics(1L, 0, 2);
    List<Map<String, Object>> secondPage = service.diagnostics(1L, 2, 2);

    assertThat(service.diagnosticCount(1L)).isEqualTo(4);
    assertThat(firstPage).hasSize(2);
    assertThat(firstPage.get(0)).containsEntry("kind", "TABLE_TASK").containsEntry("taskId", 11L);
    assertThat(firstPage.get(1)).containsEntry("kind", "TABLE_TASK").containsEntry("taskId", 12L);
    assertThat(firstPage.get(0)).containsKeys(
        "rawError", "retryCount", "maxRetryCount", "errorObservedAt", "elapsedMs", "details");
    assertThat(firstPage.get(0).get("details"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("sourceTable", "gitlab_issues")
        .containsEntry("taskStage", "SCAN");
    assertThat(secondPage).hasSize(2);
    assertThat(secondPage.get(0)).containsEntry("kind", "AUTHORITATIVE_SCOPE");
    assertThat(secondPage.get(0).get("details"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("scopeSignature", "signature-21")
        .containsEntry("childTable", "gitlab_issue_members");
  }

  @Test
  void handover_snapshot_is_merged_and_deduplicated_by_kind_and_task() {
    insertProjectionTask(401L, 1L, "FAILED", "REQUIRES_DECISION");
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("kind", "PROJECTION");
    payload.put("taskId", 401L);
    payload.put("originalRunId", "1");
    payload.put("newRunId", "FACT_REFRESH-9");
    payload.put("originalStatus", "FAILED");
    payload.put("originalDisposition", "REQUIRES_DECISION");
    payload.put("rawError", "投影原始失败原因");
    payload.put("retryCount", 2);
    payload.put("maxRetryCount", 3);
    payload.put("scopeType", "PROJECT");
    payload.put("scopeKey", "project-1");
    payload.put("targetGeneration", 8L);
    String payloadJson =
        new JsonUtils(new ObjectMapper()).toJson(payload);
    insertEventWithPayload(1L, "FACT_PROJECTION_TASK_TAKEOVER_SNAPSHOT", "投影任务已由维护人员继续", payloadJson);
    insertEventWithPayload(1L, "FACT_PROJECTION_TASK_TAKEOVER_SNAPSHOT", "投影任务已由维护人员继续", payloadJson);

    List<Map<String, Object>> items = service.diagnostics(1L, 0, 20);

    assertThat(items).hasSize(2);
    Map<String, Object> snapshot =
        items.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("handoverSnapshot")))
            .findFirst()
            .orElseThrow();
    assertThat(snapshot).containsEntry("rawError", "投影原始失败原因").containsEntry("taskId", 401L);
    assertThat(snapshot.get("details"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("targetGeneration", 8L);
    assertThat(service.diagnosticCount(1L)).isEqualTo(2);
  }

  @Test
  void table_task_diagnostics_show_retrying_with_error_and_skip_clean_retrying() {
    insertTableTask(31L, 1L, "gitlab_issues", "SCAN", "RETRYING", "退避重试前的扫描错误");
    insertTableTask(32L, 1L, "gitlab_merge_requests", "RECONCILE", "RETRYING", null);
    insertTableTask(33L, 1L, "gitlab_users", "SCAN", "TIMEOUT", "租约超时");

    List<Map<String, Object>> items = service.diagnostics(1L, 0, 20);

    // 重试窗口内的真实错误必须可见；无错误的重试排程不构成定位项（重试成功前线索会随状态变化消失）。
    assertThat(items).extracting(item -> item.get("taskId")).containsExactly(31L, 33L);
    assertThat(items.getFirst())
        .containsEntry("status", "RETRYING")
        .containsEntry("rawError", "退避重试前的扫描错误");
    assertThat(service.diagnosticCount(1L)).isEqualTo(2);
    assertThat(service.summarizeRuns(List.of(1L)).get(1L).failureCount()).isEqualTo(2);
  }

  @Test
  void events_page_selects_newest_first_and_displays_ascending() {
    for (int index = 0; index < 5; index++) {
      insertEvent(1L, "RUN_CANCELLATION_REQUESTED", "事件 " + index);
    }

    List<Map<String, Object>> events = service.events(1L, 0, 3);

    assertThat(service.eventCount(1L)).isEqualTo(5);
    assertThat(events).hasSize(3);
    assertThat(events.get(0).get("message")).isEqualTo("事件 2");
    assertThat(events.get(2).get("message")).isEqualTo("事件 4");
  }

  @Test
  void pending_list_includes_fact_task_without_parent_run() {
    insertFactTaskWithoutRun(500L, "REQUIRES_DECISION", "事实原始失败原因");
    insertProjectionTask(401L, 1L, "FAILED", "REQUIRES_DECISION");
    insertFactFailure(302L, 1L, "FAILED", "NONE");

    List<Map<String, Object>> pending = service.pendingTasks(7L, "default", 0, 10);

    assertThat(service.pendingTaskCount(7L, "default")).isEqualTo(2);
    assertThat(pending).hasSize(2);
    assertThat(pending.get(0))
        .containsEntry("kind", "FACT_BUILD")
        .containsEntry("taskId", 500L)
        .containsEntry("expectedRunId", "manual-uuid-run")
        .containsEntry("rawError", "事实原始失败原因");
    assertThat(pending.get(1)).containsEntry("kind", "PROJECTION").containsEntry("taskId", 401L);
    assertThat(service.pendingTasks(7L, "default", 1, 1)).hasSize(1);
  }

  private void insertRun(long id, String runId, long configId, String sourceInstance) {
    jdbcTemplate.update(
        """
        insert into sync_runs (id, run_id, config_id, source_instance, run_type, trigger_type, status)
        values (?, ?, ?, ?, 'FACT_REFRESH', 'MANUAL', 'SUCCESS')
        """,
        id,
        runId,
        configId,
        sourceInstance);
  }

  private void insertTableTaskFailure(
      long id, long runId, String sourceTable, String stage, String error) {
    insertTableTask(id, runId, sourceTable, stage, "FAILED", error);
  }

  /** 表任务行：状态与错误由调用方给出，用于覆盖终态失败与退避重试两类定位分支。 */
  private void insertTableTask(
      long id, long runId, String sourceTable, String stage, String status, String error) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks (
          id, run_id, config_id, source_instance, source_table, mirror_table, task_type, status,
          row_strategy, task_stage, last_error, retry_count, max_retry_count, rows_scanned,
          rows_applied, started_at, finished_at)
        values (?, ?, 7, 'default', ?, ?, 'MIRROR', ?, 'FULL', ?, ?, 2, 3, 10, 4,
                current_timestamp - interval '5 minutes', current_timestamp - interval '4 minutes')
        """,
        id,
        runId,
        sourceTable,
        "ods_" + sourceTable,
        status,
        stage,
        error);
  }

  private void insertScopeFailure(long id, long runId) {
    jdbcTemplate.update(
        """
        insert into sync_run_authoritative_scopes (
          id, run_id, source_instance, child_table, relation_key, scope_signature,
          lookup_scope_json, status, error_message, retry_count, max_retry_count, started_at,
          finished_at)
        values (?, ?, 'default', 'gitlab_issue_members', 'issue_id', ?, '{"issue_id":"7"}',
                'FAILED', '范围失败原因', 1, 3, current_timestamp - interval '3 minutes',
                current_timestamp - interval '2 minutes')
        """,
        id,
        runId,
        "signature-" + id);
  }

  private void insertFactFailure(long id, long runId, String status, String disposition) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks (
          id, run_id, scope, config_id, source_instance, fact_type, full_build, status,
          trigger_type, retry_count, max_retry_count, manual_disposition, error_message, message,
          affected_rows, started_at, finished_at)
        values (?, ?, 'issue', 7, 'default', 'ISSUE', false, ?, 'MIRROR_SYNC', 3, 3, ?,
                '事实原始失败原因', '事实任务等待处置', 12,
                current_timestamp - interval '10 minutes', current_timestamp - interval '9 minutes')
        """,
        id,
        String.valueOf(runId),
        status,
        disposition);
  }

  private void insertFactTaskWithoutRun(long id, String disposition, String error) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks (
          id, run_id, scope, config_id, source_instance, fact_type, full_build, status,
          trigger_type, retry_count, max_retry_count, manual_disposition, error_message, message)
        values (?, 'manual-uuid-run', 'issue', 7, 'default', 'ISSUE', false, 'PAUSED', 'MANUAL',
                1, 3, ?, ?, '事实任务等待处置')
        """,
        id,
        disposition,
        error);
  }

  private void insertProjectionTask(long id, long runId, String status, String disposition) {
    jdbcTemplate.update(
        """
        insert into fact_projection_refresh_tasks (
          id, fact_run_id, fact_build_task_id, source_instance, fact_type, scope_type, scope_key,
          target_generation, status, manual_disposition, error_message, retry_count,
          max_retry_count, recovery_count)
        values (?, ?, 301, 'default', 'ISSUE', 'PROJECT', 'project-1', 8, ?, ?, '投影失败原因',
                1, 3, 0)
        """,
        id,
        runId,
        status,
        disposition);
  }

  private void insertEvent(long runId, String eventType, String message) {
    jdbcTemplate.update(
        """
        insert into sync_run_events (run_id, config_id, source_instance, event_type, message)
        values (?, 7, 'default', ?, ?)
        """,
        runId,
        eventType,
        message);
  }

  private void insertEventWithPayload(
      long runId, String eventType, String message, String payloadJson) {
    jdbcTemplate.update(
        """
        insert into sync_run_events (run_id, config_id, source_instance, event_type, message, payload_json)
        values (?, 7, 'default', ?, ?, ?)
        """,
        runId,
        eventType,
        message,
        payloadJson);
  }

  private void createSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_events");
    jdbcTemplate.execute("drop table if exists fact_projection_refresh_tasks");
    jdbcTemplate.execute("drop table if exists fact_build_task_roots");
    jdbcTemplate.execute("drop table if exists fact_build_tasks");
    jdbcTemplate.execute("drop table if exists sync_run_authoritative_scopes");
    jdbcTemplate.execute("drop table if exists sync_run_table_tasks");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          run_id varchar(64) not null,
          config_id bigint not null,
          source_instance varchar(64) not null,
          run_type varchar(32) not null,
          trigger_type varchar(32) not null,
          status varchar(32) not null)
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_tasks (
          id bigint primary key,
          run_id bigint not null,
          config_id bigint not null,
          source_instance varchar(128) not null,
          source_table varchar(255) not null,
          mirror_table varchar(255) not null,
          task_type varchar(64) not null,
          status varchar(32) not null,
          row_strategy varchar(32) not null,
          task_stage varchar(32) not null default 'SCAN',
          last_error text,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          rows_scanned bigint not null default 0,
          rows_applied bigint not null default 0,
          lease_until timestamp,
          heartbeat_at timestamp,
          started_at timestamp,
          finished_at timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_authoritative_scopes (
          id bigint primary key,
          run_id bigint not null,
          source_instance varchar(128) not null,
          child_table varchar(255) not null,
          relation_key varchar(128) not null,
          scope_signature varchar(1024) not null,
          lookup_scope_json text not null,
          status varchar(32) not null,
          lease_expires_at timestamp,
          heartbeat_at timestamp,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          error_message text,
          started_at timestamp,
          finished_at timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table fact_build_tasks (
          id bigint primary key,
          run_id varchar(64) not null,
          scope varchar(128) not null,
          config_id bigint,
          source_instance varchar(128) not null default 'default',
          fact_type varchar(64) not null default 'ALL',
          full_build boolean not null default false,
          status varchar(32) not null,
          trigger_type varchar(32) not null default 'MANUAL',
          affected_rows integer not null default 0,
          message text,
          error_message text,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          manual_disposition varchar(32) not null default 'NONE',
          heartbeat_at timestamp,
          lease_until timestamp,
          started_at timestamp,
          finished_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table fact_build_task_roots (
          task_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          primary key (task_id, root_id))
        """);
    jdbcTemplate.execute(
        """
        create table fact_projection_refresh_tasks (
          id bigint primary key,
          fact_run_id bigint not null,
          fact_build_task_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          scope_type varchar(64) not null,
          scope_key varchar(512) not null,
          target_generation bigint not null,
          status varchar(32) not null default 'QUEUED',
          manual_disposition varchar(32) not null default 'NONE',
          lease_owner varchar(128),
          lease_until timestamp,
          heartbeat_at timestamp,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          recovery_count integer not null default 0,
          error_message text,
          started_at timestamp,
          finished_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_events (
          id bigserial primary key,
          run_id bigint,
          config_id bigint,
          source_instance varchar(128),
          event_type varchar(64) not null,
          table_name varchar(255),
          message text,
          payload_json text,
          created_at timestamp not null default current_timestamp)
        """);
  }
}
