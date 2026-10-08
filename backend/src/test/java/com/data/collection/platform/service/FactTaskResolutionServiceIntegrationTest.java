package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactManualDisposition;
import com.data.collection.platform.entity.FactResumeMode;
import com.data.collection.platform.entity.FactTaskKind;
import com.data.collection.platform.entity.FactTaskResolutionAction;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import com.data.collection.platform.service.sync.SyncRunPayload;
import com.data.collection.platform.service.sync.SyncRunPublicationFenceService;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 人工处置命令的真实 PostgreSQL 证据。
 *
 * <p>夹具建立事实任务、根归属、投影任务、版本头与运行事件五张表的真实结构；运行创建入口用替身返回
 * 可核对的运行行，因此断言覆盖的是"命令在一个事务内完成校验、移交与严格诊断"这一契约本身。
 */
class FactTaskResolutionServiceIntegrationTest {
  private static final long SOURCE_RUN_ID = 500L;

  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private FactBuildTaskService factBuildTaskService;
  private FactProjectionTaskService projectionTaskService;
  private SyncRunPublicationFenceService publicationFenceService;
  private SyncRunSubmissionService submissionService;
  private SyncRunMapper syncRunMapper;
  private JsonUtils jsonUtils;
  private FactTaskResolutionService resolutionService;
  private final AtomicLong runIdSequence = new AtomicLong(900L);
  private final Map<Long, SyncRun> runs = new HashMap<>();

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_task_resolution_test");
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
    jsonUtils =
        new JsonUtils(
            new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    GitlabConfigService configService = mock(GitlabConfigService.class);
    when(configService.getConfigById(1L)).thenReturn(config());
    publicationFenceService = mock(SyncRunPublicationFenceService.class);
    SyncRunEventRecorder eventRecorder = new SyncRunEventRecorder(jdbcTemplate);
    factBuildTaskService = new FactBuildTaskService(jdbcTemplate, dataSource, eventRecorder);
    projectionTaskService = new FactProjectionTaskService(jdbcTemplate, publicationFenceService);
    submissionService = mock(SyncRunSubmissionService.class);
    syncRunMapper = mock(SyncRunMapper.class);
    runs.clear();
    insertRun(SOURCE_RUN_ID, "FACT_REFRESH-source", "SUCCESS");
    when(syncRunMapper.selectById(anyLong()))
        .thenAnswer(invocation -> runs.get(invocation.getArgument(0, Long.class)));
    when(submissionService.submitRun(
            any(), any(), any(), any(), anyString(), any(), any(), any(), any(), anyMap()))
        .thenAnswer(invocation -> createRun(invocation.getArgument(9)));
    resolutionService =
        new FactTaskResolutionService(
            configService,
            factBuildTaskService,
            projectionTaskService,
            submissionService,
            eventRecorder,
            syncRunMapper,
            jsonUtils);
  }

  @Test
  void resuming_paused_fact_task_hands_roots_to_new_run() {
    insertFactTask(101L, "PAUSED", "REQUIRES_DECISION", "ISSUE", false, "2026-09-30 09:00:00");
    insertRoot(101L, 11L);
    insertRoot(101L, 12L);
    insertHead(11L, "ISSUE", 3L, 1L);
    insertHead(12L, "ISSUE", 3L, 3L);

    com.data.collection.platform.entity.FactTaskResolutionResult result = resolve(
        FactTaskKind.FACT_BUILD, 101L, String.valueOf(SOURCE_RUN_ID),
        FactTaskResolutionAction.RESUME, "ORIGINAL");

    assertThat(result.disposition()).isEqualTo(FactManualDisposition.RESUMED);
    assertThat(result.newRunId()).isNotBlank();
    long newRunId = Long.parseLong(result.newRunId().replaceAll("[^0-9]", ""));
    long newTaskId = factBuildTaskService.findResumedTask(101L).id();
    assertThat(statusOfFactTask(101L)).isEqualTo("SKIPPED");
    assertThat(dispositionOfFactTask(101L)).isEqualTo("RESUMED");
    assertThat(factBuildTaskService.loadAssignedRootIds(newTaskId)).containsExactly(11L, 12L);
    assertThat(factBuildTaskService.loadAssignedRootIds(101L)).isEmpty();
    assertThat(statusOfFactTask(newTaskId)).isEqualTo("QUEUED");
    assertThat(runIdOfFactTask(newTaskId)).isEqualTo(String.valueOf(newRunId));
    assertThat(eventCount(SOURCE_RUN_ID, "FACT_TASK_RESUMED_SNAPSHOT")).isEqualTo(1);
    assertThat(eventCount(newRunId, "FACT_TASK_RESUMED")).isEqualTo(1);
    assertThat(result.remainingPendingUpdates()).isEqualTo(1);
  }

  @Test
  void cancel_releases_roots_without_touching_version_heads() {
    insertFactTask(102L, "PAUSED", "REQUIRES_DECISION", "ISSUE", false, "2026-09-30 09:00:00");
    insertRoot(102L, 21L);
    insertHead(21L, "ISSUE", 4L, 0L);

    var result =
        resolve(
            FactTaskKind.FACT_BUILD, 102L, String.valueOf(SOURCE_RUN_ID),
            FactTaskResolutionAction.CANCEL, null);

    assertThat(result.disposition()).isEqualTo(FactManualDisposition.CANCELLED);
    assertThat(result.newRunId()).isNull();
    assertThat(statusOfFactTask(102L)).isEqualTo("SKIPPED");
    assertThat(dispositionOfFactTask(102L)).isEqualTo("CANCELLED");
    assertThat(factBuildTaskService.loadAssignedRootIds(102L)).isEmpty();
    assertThat(publishedVersionOfHead(21L)).isZero();
    assertThat(latestChangeVersionOfHead(21L)).isEqualTo(4L);
  }

  @Test
  void original_resume_is_rejected_when_roots_were_released() {
    insertFactTask(103L, "FAILED", "NONE", "ISSUE", false, null);

    assertThatThrownBy(
            () ->
                resolve(
                    FactTaskKind.FACT_BUILD, 103L, String.valueOf(SOURCE_RUN_ID),
                    FactTaskResolutionAction.RESUME, "ORIGINAL"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已释放根");
    assertThat(statusOfFactTask(103L)).isEqualTo("FAILED");
  }

  @Test
  void current_pending_resume_assigns_currently_pending_roots() {
    insertFactTask(104L, "PAUSED", "REQUIRES_DECISION", "ISSUE", false, "2026-09-30 09:00:00");
    insertRoot(104L, 31L);
    insertHead(41L, "ISSUE", 3L, 3L);
    insertHead(42L, "ISSUE", 5L, 1L);

    resolve(
        FactTaskKind.FACT_BUILD, 104L, String.valueOf(SOURCE_RUN_ID),
        FactTaskResolutionAction.RESUME, "CURRENT_PENDING");

    long newTaskId = factBuildTaskService.findResumedTask(104L).id();
    assertThat(factBuildTaskService.loadAssignedRootIds(newTaskId)).containsExactly(42L);
    assertThat(factBuildTaskService.loadAssignedRootIds(104L)).isEmpty();
  }

  @Test
  void full_resume_keeps_full_intent_without_roots() {
    insertFactTask(105L, "PAUSED", "REQUIRES_DECISION", "MERGE_REQUEST", true, "2026-09-30 09:00:00");

    resolve(
        FactTaskKind.FACT_BUILD, 105L, String.valueOf(SOURCE_RUN_ID),
        FactTaskResolutionAction.RESUME, "ORIGINAL");

    long newTaskId = factBuildTaskService.findResumedTask(105L).id();
    assertThat(fullBuildOfFactTask(newTaskId)).isTrue();
    assertThat(factBuildTaskService.loadAssignedRootIds(newTaskId)).isEmpty();
  }

  @Test
  void resume_is_rejected_when_another_activity_occupies_the_source() {
    insertFactTask(106L, "PAUSED", "REQUIRES_DECISION", "ISSUE", false, "2026-09-30 09:00:00");
    insertRoot(106L, 51L);
    when(submissionService.submitRun(
            any(), any(), any(), any(), anyString(), any(), any(), any(), any(), anyMap()))
        .thenReturn(
            new SyncRunSubmissionResult(
                SOURCE_RUN_ID,
                SyncType.COMPENSATION,
                SyncStatus.RUNNING,
                SyncSubmissionAction.REUSED_ACTIVE,
                LocalDateTime.now(),
                "复用现有运行"));

    assertThatThrownBy(
            () ->
                resolve(
                    FactTaskKind.FACT_BUILD, 106L, String.valueOf(SOURCE_RUN_ID),
                    FactTaskResolutionAction.RESUME, "ORIGINAL"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已有活动");
    assertThat(statusOfFactTask(106L)).isEqualTo("PAUSED");
    assertThat(factBuildTaskService.loadAssignedRootIds(106L)).containsExactly(51L);
  }

  @Test
  void command_rejects_mismatched_original_run() {
    insertFactTask(107L, "PAUSED", "REQUIRES_DECISION", "ISSUE", false, "2026-09-30 09:00:00");

    assertThatThrownBy(
            () ->
                resolve(
                    FactTaskKind.FACT_BUILD, 107L, "999999",
                    FactTaskResolutionAction.CANCEL, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("归属已变化");
  }

  @Test
  void projection_cancel_marks_failed_with_cancel_disposition() {
    insertProjectionTask(201L, SOURCE_RUN_ID, "FAILED", "REQUIRES_DECISION", "原始失败原因");

    var result =
        resolve(
            FactTaskKind.PROJECTION, 201L, String.valueOf(SOURCE_RUN_ID),
            FactTaskResolutionAction.CANCEL, null);

    assertThat(result.disposition()).isEqualTo(FactManualDisposition.CANCELLED);
    assertThat(statusOfProjectionTask(201L)).isEqualTo("FAILED");
    assertThat(dispositionOfProjectionTask(201L)).isEqualTo("CANCELLED");
    assertThat(errorOfProjectionTask(201L)).isEqualTo("原始失败原因");
    verify(publicationFenceService).advanceAfterProjectionTask(201L);
  }

  @Test
  void projection_resume_moves_the_same_row_and_keeps_original_diagnostics() {
    insertProjectionTask(202L, SOURCE_RUN_ID, "FAILED", "REQUIRES_DECISION", "投影原始失败原因");

    var first =
        resolve(
            FactTaskKind.PROJECTION, 202L, String.valueOf(SOURCE_RUN_ID),
            FactTaskResolutionAction.RESUME, null);

    long newRunId = Long.parseLong(first.newRunId().replaceAll("[^0-9]", ""));
    assertThat(statusOfProjectionTask(202L)).isEqualTo("QUEUED");
    assertThat(dispositionOfProjectionTask(202L)).isEqualTo("NONE");
    assertThat(factRunIdOfProjectionTask(202L)).isEqualTo(newRunId);
    assertThat(retryCountOfProjectionTask(202L)).isZero();
    assertThat(errorOfProjectionTask(202L)).isNull();
    assertThat(diagnosticPayload(SOURCE_RUN_ID, "FACT_PROJECTION_TASK_TAKEOVER_SNAPSHOT"))
        .contains("投影原始失败原因");
    assertThat(diagnosticPayload(newRunId, "FACT_PROJECTION_TASK_TAKEOVER")).contains("202");

    var repeated =
        resolve(
            FactTaskKind.PROJECTION, 202L, String.valueOf(SOURCE_RUN_ID),
            FactTaskResolutionAction.RESUME, null);
    assertThat(repeated.newRunId()).isEqualTo(first.newRunId());
    assertThat(repeated.disposition()).isEqualTo(FactManualDisposition.RESUMED);
  }

  @Test
  void projection_resolution_rejects_tasks_that_are_not_failed_or_awaiting_decision() {
    // 成功与仍在自动执行（无租约也未被停放）的任务都不在可处置范围：只允许接管终态失败或等待人工决定的任务。
    insertProjectionTask(203L, SOURCE_RUN_ID, "project-203", "SUCCESS", "NONE", null);
    insertProjectionTask(204L, SOURCE_RUN_ID, "project-204", "RUNNING", "NONE", "进行中");

    for (long taskId : List.of(203L, 204L)) {
      assertThatThrownBy(
              () ->
                  resolve(
                      FactTaskKind.PROJECTION, taskId, String.valueOf(SOURCE_RUN_ID),
                      FactTaskResolutionAction.CANCEL, null))
          .isInstanceOf(BizException.class)
          .hasMessageContaining("不可处置");
    }
    assertThat(statusOfProjectionTask(203L)).isEqualTo("SUCCESS");
    assertThat(dispositionOfProjectionTask(203L)).isEqualTo("NONE");
    assertThat(statusOfProjectionTask(204L)).isEqualTo("RUNNING");
  }

  private com.data.collection.platform.entity.FactTaskResolutionResult resolve(
      FactTaskKind kind,
      long taskId,
      String expectedTaskRunId,
      FactTaskResolutionAction action,
      String resumeMode) {
    return resolutionService.resolvePendingTask(
        1L, kind, taskId, expectedTaskRunId, action, resumeMode);
  }

  /**
   * 替身提交入口：写出真实运行行并返回该运行，使命令的归属与诊断写入可被核对。
   */
  private SyncRunSubmissionResult createRun(Map<String, Object> extraPayload) {
    long runId = runIdSequence.incrementAndGet();
    SyncRunPayload payload =
        SyncRunPayload.create(null, null, null, List.of(), null, null, null);
    String payloadJson = jsonUtils.toJson(payload.toMap(extraPayload));
    insertRun(runId, "FACT_REFRESH-" + runId, "QUEUED");
    jdbcTemplate.update("update sync_runs set payload_json = ? where id = ?", payloadJson, runId);
    SyncRun run = runs.get(runId);
    run.setPayloadJson(payloadJson);
    return new SyncRunSubmissionResult(
        runId,
        SyncType.COMPENSATION,
        SyncStatus.QUEUED,
        SyncSubmissionAction.QUEUED,
        LocalDateTime.now(),
        "已入队");
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    return config;
  }

  private void insertRun(long id, String runId, String status) {
    jdbcTemplate.update(
        """
        insert into sync_runs (
          id, run_id, config_id, source_instance, run_type, trigger_type, status,
          exclusive_scope, cancel_requested, payload_json, created_at, updated_at)
        values (?, ?, 1, 'default', 'FACT_REFRESH', 'MANUAL', ?, 'default', false, null,
                current_timestamp, current_timestamp)
        """,
        id,
        runId,
        status);
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId(runId);
    run.setConfigId(1L);
    run.setSourceInstance("default");
    run.setStatus(com.data.collection.platform.entity.sync.SyncRunStatus.valueOf(status));
    runs.put(id, run);
  }

  private void insertFactTask(
      long id, String status, String disposition, String factType, boolean full, String finishedAt) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks (
          id, run_id, scope, config_id, source_instance, fact_type, full_build, status,
          trigger_type, retry_count, max_retry_count, run_after, manual_disposition,
          error_message, message, finished_at, created_at, updated_at)
        values (?, ?, 'issue', 1, 'default', ?, ?, ?, 'MIRROR_SYNC', 1, 3,
                current_timestamp, ?, '事实原始失败原因', '停放等待决定', %s,
                current_timestamp, current_timestamp)
        """
            .formatted(finishedAt == null ? "null" : "timestamp '" + finishedAt + "'"),
        id,
        String.valueOf(SOURCE_RUN_ID),
        factType,
        full,
        status,
        disposition);
  }

  private void insertRoot(long taskId, long rootId) {
    jdbcTemplate.update(
        """
        insert into fact_build_task_roots (task_id, source_instance, fact_type, root_id)
        values (?, 'default', 'ISSUE', ?)
        """,
        taskId,
        rootId);
  }

  private void insertHead(long rootId, String factType, long latest, long published) {
    jdbcTemplate.update(
        """
        insert into fact_change_heads (
          source_instance, fact_type, root_id, latest_change_version, published_version,
          updated_at)
        values ('default', ?, ?, ?, ?, current_timestamp)
        """,
        factType,
        rootId,
        latest,
        published);
  }

  private void insertProjectionTask(
      long id, long factRunId, String status, String disposition, String errorMessage) {
    insertProjectionTask(id, factRunId, "project-1", status, disposition, errorMessage);
  }

  /** 投影任务行：范围键可指定，用于同一事实任务下并存多条投影任务。 */
  private void insertProjectionTask(
      long id, long factRunId, String scopeKey, String status, String disposition, String errorMessage) {
    jdbcTemplate.update(
        """
        insert into fact_projection_refresh_tasks (
          id, fact_run_id, fact_build_task_id, source_instance, fact_type, scope_type, scope_key,
          target_generation, status, manual_disposition, error_message, retry_count,
          max_retry_count, recovery_count, run_after, created_at, updated_at)
        values (?, ?, 101, 'default', 'ISSUE', 'PROJECT', ?, 7, ?, ?, ?, 1, 3, 0,
                current_timestamp, current_timestamp, current_timestamp)
        """,
        id,
        factRunId,
        scopeKey,
        status,
        disposition,
        errorMessage);
  }

  private String statusOfFactTask(long id) {
    return jdbcTemplate.queryForObject(
        "select status from fact_build_tasks where id = ?", String.class, id);
  }

  private String dispositionOfFactTask(long id) {
    return jdbcTemplate.queryForObject(
        "select manual_disposition from fact_build_tasks where id = ?", String.class, id);
  }

  private String runIdOfFactTask(long id) {
    return jdbcTemplate.queryForObject(
        "select run_id from fact_build_tasks where id = ?", String.class, id);
  }

  private boolean fullBuildOfFactTask(long id) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select full_build from fact_build_tasks where id = ?", Boolean.class, id));
  }

  private long publishedVersionOfHead(long rootId) {
    return jdbcTemplate.queryForObject(
        "select published_version from fact_change_heads where root_id = ?", Long.class, rootId);
  }

  private long latestChangeVersionOfHead(long rootId) {
    return jdbcTemplate.queryForObject(
        "select latest_change_version from fact_change_heads where root_id = ?",
        Long.class,
        rootId);
  }

  private int eventCount(long runId, String eventType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_run_events where run_id = ? and event_type = ?",
            Integer.class,
            runId,
            eventType);
    return count == null ? 0 : count;
  }

  private String diagnosticPayload(long runId, String eventType) {
    return jdbcTemplate.queryForObject(
        "select payload_json from sync_run_events where run_id = ? and event_type = ? order by id desc limit 1",
        String.class,
        runId,
        eventType);
  }

  private String statusOfProjectionTask(long id) {
    return jdbcTemplate.queryForObject(
        "select status from fact_projection_refresh_tasks where id = ?", String.class, id);
  }

  private String dispositionOfProjectionTask(long id) {
    return jdbcTemplate.queryForObject(
        "select manual_disposition from fact_projection_refresh_tasks where id = ?",
        String.class,
        id);
  }

  private String errorOfProjectionTask(long id) {
    return jdbcTemplate.queryForObject(
        "select error_message from fact_projection_refresh_tasks where id = ?", String.class, id);
  }

  private long factRunIdOfProjectionTask(long id) {
    return jdbcTemplate.queryForObject(
        "select fact_run_id from fact_projection_refresh_tasks where id = ?", Long.class, id);
  }

  private int retryCountOfProjectionTask(long id) {
    return jdbcTemplate.queryForObject(
        "select retry_count from fact_projection_refresh_tasks where id = ?", Integer.class, id);
  }

  private void createSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_events");
    jdbcTemplate.execute("drop table if exists fact_projection_refresh_tasks");
    jdbcTemplate.execute("drop table if exists fact_build_task_roots");
    jdbcTemplate.execute("drop table if exists fact_change_heads");
    jdbcTemplate.execute("drop table if exists source_fact_publication_states");
    jdbcTemplate.execute("drop table if exists fact_build_tasks");
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
          status varchar(32) not null,
          exclusive_scope varchar(64) not null,
          cancel_requested boolean not null default false,
          payload_json text,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table fact_build_tasks (
          id bigint generated by default as identity (start with 1000) primary key,
          run_id varchar(64) not null,
          scope varchar(128) not null,
          config_id bigint,
          source_instance varchar(128) not null default 'default',
          fact_type varchar(64) not null default 'ALL',
          full_build boolean not null default false,
          status varchar(32) not null,
          trigger_type varchar(32) not null default 'MANUAL',
          lock_owner varchar(128),
          affected_rows integer not null default 0,
          message text,
          error_message text,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          run_after timestamp not null default current_timestamp,
          heartbeat_at timestamp,
          lease_until timestamp,
          payload_json text,
          manual_disposition varchar(32) not null default 'NONE',
          wait_reason varchar(64),
          resumed_from_task_id bigint,
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
          id bigint generated by default as identity (start with 2000) primary key,
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
          run_after timestamp not null default current_timestamp,
          error_message text,
          started_at timestamp,
          finished_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp,
          unique (fact_build_task_id, scope_type, scope_key))
        """);
    jdbcTemplate.execute(
        """
        create table fact_change_heads (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          latest_change_version bigint not null,
          published_version bigint not null default 0,
          updated_at timestamp not null default current_timestamp,
          primary key (source_instance, fact_type, root_id))
        """);
    jdbcTemplate.execute(
        """
        create table source_fact_publication_states (
          config_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          readiness_status varchar(16) not null,
          primary key (config_id, source_instance, fact_type))
        """);
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states (
          config_id, source_instance, fact_type, readiness_status)
        values (1, 'default', 'ISSUE', 'READY'), (1, 'default', 'MERGE_REQUEST', 'READY')
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_events (
          id bigserial primary key,
          run_id bigint references sync_runs(id) on delete cascade,
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
