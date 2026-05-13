package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableShardProbe;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import com.fasterxml.jackson.databind.ObjectMapper;

class GitlabTableSyncWorkerServiceTest {
  private GitlabTableSyncTaskMapper taskMapper;
  private GitlabTableSyncStateMapper stateMapper;
  private GitlabSyncJobMapper jobMapper;
  private GitlabConfigService configService;
  private GitlabExternalDbService externalDbService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabMirrorTableStorageService storageService;
  private FactBuildTaskService factBuildTaskService;
  private GitlabSyncLogService logService;
  private GitlabTableSyncWorkerService service;

  @BeforeEach
  void setUp() {
    taskMapper = mock(GitlabTableSyncTaskMapper.class);
    stateMapper = mock(GitlabTableSyncStateMapper.class);
    jobMapper = mock(GitlabSyncJobMapper.class);
    configService = mock(GitlabConfigService.class);
    externalDbService = mock(GitlabExternalDbService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    storageService = mock(GitlabMirrorTableStorageService.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    logService = mock(GitlabSyncLogService.class);
    when(taskMapper.update(any(), ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1);
    service = new GitlabTableSyncWorkerService(
        taskMapper,
        stateMapper,
        jobMapper,
        configService,
        externalDbService,
        mirrorSchemaService,
        storageService,
        new GitlabMirrorProperties(),
        factBuildTaskService,
        logService,
        new JsonUtils(new ObjectMapper()));
  }

  @Test
  void shouldExecuteOneBatchAndQueueContinuationWhenBatchIsFull() {
    GitlabTableSyncTask task = task();
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setStatus(SyncStatus.PENDING);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));
    List<Map<String, Object>> rows = List.of(
        Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 1, 2, 3, 4, 5)),
        Map.of("id", 102L, "updated_at", LocalDateTime.of(2026, 1, 2, 3, 4, 6)));

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(jobMapper.selectById(9L)).thenReturn(job);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.incrementalCursorScan(
        eq(config),
        any(),
        eq(LocalDateTime.of(2025, 12, 31, 23, 55)),
        eq(null),
        eq(null),
        eq(2)))
        .thenReturn(rows);
    when(storageService.upsertBatch(schema, rows, 21L)).thenReturn(new MirrorBatchWriteResult(2, 2, 0));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(task);

    assertThat(task.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(task.getRowsScanned()).isEqualTo(2L);
    assertThat(state.getLastWatermarkAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 6));
    assertThat(state.getLastCursorPk()).isEqualTo("102");
    assertThat(state.isDirtyFlag()).isTrue();

    ArgumentCaptor<GitlabTableSyncTask> continuationCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(continuationCaptor.capture());
    GitlabTableSyncTask continuation = continuationCaptor.getValue();
    assertThat(continuation.getCursorUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 6));
    assertThat(continuation.getCursorPk()).isEqualTo("102");
    assertThat(continuation.getStatus()).isEqualTo(SyncStatus.PENDING);
    verify(taskMapper, atLeast(2)).update(eq(null), ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any());
  }

  @Test
  void shouldRecoverTimedOutRunningTaskByCreatingRetryTask() {
    GitlabTableSyncTask staleTask = task();
    staleTask.setStatus(SyncStatus.RUNNING);
    staleTask.setRetryCount(1);
    staleTask.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
    GitlabTableSyncState state = state();
    when(taskMapper.selectList(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(List.of(staleTask));
    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);

    int recovered = service.recoverTimedOutTasks();

    assertThat(recovered).isEqualTo(1);
    assertThat(staleTask.getStatus()).isEqualTo(SyncStatus.RETRYING);
    assertThat(state.isDirtyFlag()).isTrue();
    assertThat(state.getLastError()).isEqualTo("Table sync task lease timed out");

    ArgumentCaptor<GitlabTableSyncTask> retryCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(retryCaptor.capture());
    GitlabTableSyncTask retryTask = retryCaptor.getValue();
    assertThat(retryTask.getStatus()).isEqualTo(SyncStatus.PENDING);
    assertThat(retryTask.getRetryCount()).isEqualTo(2);
    assertThat(retryTask.getSourceTable()).isEqualTo("issues");
    assertThat(retryTask.getRunAfter()).isAfter(LocalDateTime.now());
  }

  @Test
  void shouldSkipTimeoutRecoveryWhenTaskHeartbeatWasRenewedAfterSelection() {
    GitlabTableSyncTask staleSelection = task();
    staleSelection.setStatus(SyncStatus.RUNNING);
    staleSelection.setRetryCount(1);
    staleSelection.setLeaseUntil(LocalDateTime.now().minusMinutes(1));
    GitlabTableSyncTask renewedTask = task();
    renewedTask.setStatus(SyncStatus.RUNNING);
    renewedTask.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
    renewedTask.setHeartbeatAt(LocalDateTime.now());

    when(taskMapper.selectList(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(List.of(staleSelection));
    when(taskMapper.selectById(21L)).thenReturn(renewedTask);

    int recovered = service.recoverTimedOutTasks();

    assertThat(recovered).isZero();
    verify(taskMapper, times(0)).insert(ArgumentMatchers.<GitlabTableSyncTask>any());
  }

  @Test
  void shouldRetryFailedTableWithoutFailingWholeJobImmediately() {
    GitlabTableSyncTask task = task();
    GitlabTableSyncState state = state();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setStatus(SyncStatus.RUNNING);

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenThrow(new IllegalStateException("source unavailable"));
    when(jobMapper.selectById(9L)).thenReturn(job);
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(task);

    assertThat(task.getStatus()).isEqualTo(SyncStatus.RETRYING);
    assertThat(state.isDirtyFlag()).isTrue();
    assertThat(state.getLastError()).isEqualTo("source unavailable");
    assertThat(job.getStatus()).isEqualTo(SyncStatus.RUNNING);

    ArgumentCaptor<GitlabTableSyncTask> retryCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(retryCaptor.capture());
    GitlabTableSyncTask retryTask = retryCaptor.getValue();
    assertThat(retryTask.getStatus()).isEqualTo(SyncStatus.PENDING);
    assertThat(retryTask.getRetryCount()).isEqualTo(1);
    assertThat(retryTask.getRunAfter()).isAfter(LocalDateTime.now());
  }

  @Test
  void shouldCreateRepairTaskWhenDailyVerificationFindsDrift() {
    GitlabTableSyncTask verifyTask = task();
    verifyTask.setTaskType(GitlabTableSyncTaskType.DAILY_VERIFY);
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));
    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.probeTable(eq(config), any()))
        .thenReturn(new GitlabTableProbe(10L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "10"));
    when(storageService.probeMirrorTable(schema))
        .thenReturn(new GitlabTableProbe(9L, LocalDateTime.of(2026, 1, 2, 3, 3), "1", "9"));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(verifyTask);

    assertThat(verifyTask.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(state.isDirtyFlag()).isTrue();
    assertThat(state.getSourceRowCount()).isEqualTo(10L);
    assertThat(state.getMirrorRowCount()).isEqualTo(9L);

    ArgumentCaptor<GitlabTableSyncTask> repairCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(repairCaptor.capture());
    GitlabTableSyncTask repairTask = repairCaptor.getValue();
    assertThat(repairTask.getTaskType()).isEqualTo(GitlabTableSyncTaskType.FULL_REPAIR);
    assertThat(repairTask.getStatus()).isEqualTo(SyncStatus.PENDING);
    assertThat(repairTask.getWatermarkAt()).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
  }

  @Test
  void shouldCreateDeleteReconcileTaskWhenDailyVerificationFindsExtraMirrorRows() {
    GitlabTableSyncTask verifyTask = task();
    verifyTask.setTaskType(GitlabTableSyncTaskType.DAILY_VERIFY);
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));
    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.probeTable(eq(config), any()))
        .thenReturn(new GitlabTableProbe(9L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "9"));
    when(storageService.probeMirrorTable(schema))
        .thenReturn(new GitlabTableProbe(10L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "10"));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(verifyTask);

    ArgumentCaptor<GitlabTableSyncTask> taskCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    GitlabTableSyncTask reconcileTask = taskCaptor.getValue();
    assertThat(reconcileTask.getTaskType()).isEqualTo(GitlabTableSyncTaskType.DELETE_RECONCILE);
    assertThat(reconcileTask.getStatus()).isEqualTo(SyncStatus.PENDING);
  }

  @Test
  void shouldCreateShardRepairTaskWhenDailyVerificationFindsChecksumDrift() {
    GitlabTableSyncTask verifyTask = task();
    verifyTask.setTaskType(GitlabTableSyncTaskType.DAILY_VERIFY);
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));
    GitlabTableProbe sameProbe = new GitlabTableProbe(10L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "10");

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.probeTable(eq(config), any())).thenReturn(sameProbe);
    when(storageService.probeMirrorTable(schema)).thenReturn(sameProbe);
    when(externalDbService.probeTableShards(eq(config), any(), eq(schema), eq(2)))
        .thenReturn(List.of(new GitlabTableShardProbe(
            "0a", 5L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "9", "source-checksum")));
    when(storageService.probeMirrorTableShards(schema, 2))
        .thenReturn(List.of(new GitlabTableShardProbe(
            "0a", 5L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "9", "mirror-checksum")));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(verifyTask);

    ArgumentCaptor<GitlabTableSyncTask> taskCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    GitlabTableSyncTask repairTask = taskCaptor.getValue();
    assertThat(repairTask.getTaskType()).isEqualTo(GitlabTableSyncTaskType.SHARD_REPAIR);
    assertThat(repairTask.getCursorPk()).contains("\"shardKey\":\"0a\"");
    assertThat(state.isDirtyFlag()).isTrue();
  }

  @Test
  void shouldSkipShardChecksumProbeWhenDailyVerificationProbeMatchesLastCleanState() {
    GitlabTableSyncTask verifyTask = task();
    verifyTask.setTaskType(GitlabTableSyncTaskType.DAILY_VERIFY);
    GitlabTableSyncState state = state();
    state.setSourceRowCount(10L);
    state.setMirrorRowCount(10L);
    state.setSourceMaxUpdatedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
    state.setSchemaFingerprint("schema-v1");
    state.setLastFullVerifiedAt(LocalDateTime.of(2026, 1, 2, 4, 0));
    state.setDirtyFlag(false);
    GitlabSyncConfig config = config();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setConfigId(3L);
    job.setJobType(GitlabSyncJobType.DAILY_VERIFY);
    job.setStatus(SyncStatus.RUNNING);
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setSchemaFingerprint("schema-v1");
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));
    GitlabTableProbe sameProbe = new GitlabTableProbe(10L, LocalDateTime.of(2026, 1, 2, 3, 4), "1", "10");

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            registry));
    when(externalDbService.probeTable(eq(config), any())).thenReturn(sameProbe);
    when(storageService.probeMirrorTable(schema)).thenReturn(sameProbe);
    when(jobMapper.selectById(9L)).thenReturn(job);
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any()))
        .thenReturn(0L, 0L, 0L, 1L, 0L);

    service.executeTask(verifyTask);

    assertThat(verifyTask.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(job.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(state.isDirtyFlag()).isFalse();
    assertThat(state.getLastError()).isEmpty();
    verify(externalDbService, never()).probeTableShards(any(), any(), any(), any(Integer.class));
    verify(storageService, never()).probeMirrorTableShards(any(), any(Integer.class));
    verify(taskMapper, never()).insert(ArgumentMatchers.<GitlabTableSyncTask>any());
    verify(factBuildTaskService, never()).enqueueMirrorRefreshTasks(any(), eq(true));
  }

  @Test
  void shouldExecuteDeleteReconcileTaskAndQueueContinuationWhenBatchIsFull() {
    GitlabTableSyncTask task = task();
    task.setTaskType(GitlabTableSyncTaskType.DELETE_RECONCILE);
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(new SourceTableColumn("id", "bigint", false, 1)));
    List<Map<String, Object>> mirrorKeys = List.of(Map.of("id", "101"), Map.of("id", "102"));
    MirrorPrimaryKeyBatch batch = new MirrorPrimaryKeyBatch(mirrorKeys, "[\"102\"]");

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(storageService.listActivePrimaryKeys(schema, null, 2)).thenReturn(batch);
    when(externalDbService.findExistingPrimaryKeySignatures(eq(config), any(), eq(mirrorKeys)))
        .thenReturn(Set.of("101"));
    when(storageService.markRowsDeletedByPrimaryKeys(eq(schema), eq(List.of(Map.of("id", "102"))), eq(21L)))
        .thenReturn(1);
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(task);

    assertThat(task.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(task.getRowsScanned()).isEqualTo(2L);
    assertThat(task.getRowsApplied()).isEqualTo(1L);
    assertThat(state.isDirtyFlag()).isTrue();

    ArgumentCaptor<GitlabTableSyncTask> continuationCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(continuationCaptor.capture());
    GitlabTableSyncTask continuation = continuationCaptor.getValue();
    assertThat(continuation.getTaskType()).isEqualTo(GitlabTableSyncTaskType.DELETE_RECONCILE);
    assertThat(continuation.getCursorPk()).isEqualTo("[\"102\"]");
  }

  @Test
  void shouldExecuteShardRepairTaskAndQueueContinuationWhenBatchIsFull() {
    GitlabTableSyncTask task = task();
    task.setTaskType(GitlabTableSyncTaskType.SHARD_REPAIR);
    task.setCursorPk("{\"shardKey\":\"0a\",\"rowCursor\":\"\"}");
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));
    List<Map<String, Object>> sourceRows = List.of(
        Map.of(
            "id", 101L,
            "title", "A",
            "updated_at", LocalDateTime.of(2026, 1, 2, 3, 4),
            "pk_signature", "101"),
        Map.of(
            "id", 102L,
            "title", "B",
            "updated_at", LocalDateTime.of(2026, 1, 2, 3, 5),
            "pk_signature", "102"));

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.shardCursorScan(eq(config), any(), eq(schema), eq("0a"), eq(null), eq(2)))
        .thenReturn(sourceRows);
    when(storageService.upsertBatch(eq(schema), any(), eq(21L))).thenReturn(new MirrorBatchWriteResult(2, 2, 0));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(1L);

    service.executeTask(task);

    assertThat(task.getStatus()).isEqualTo(SyncStatus.SUCCESS);
    assertThat(task.getRowsScanned()).isEqualTo(2L);
    assertThat(task.getRowsApplied()).isEqualTo(2L);
    assertThat(state.isDirtyFlag()).isTrue();

    ArgumentCaptor<List<Map<String, Object>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
    verify(storageService).upsertBatch(eq(schema), rowsCaptor.capture(), eq(21L));
    assertThat(rowsCaptor.getValue().get(0)).doesNotContainKey("pk_signature");

    ArgumentCaptor<GitlabTableSyncTask> continuationCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(continuationCaptor.capture());
    GitlabTableSyncTask continuation = continuationCaptor.getValue();
    assertThat(continuation.getTaskType()).isEqualTo(GitlabTableSyncTaskType.SHARD_REPAIR);
    assertThat(continuation.getCursorPk()).contains("\"shardKey\":\"0a\"");
    assertThat(continuation.getCursorPk()).contains("\"rowCursor\":\"102\"");
  }

  @Test
  void shouldEnqueueFactRefreshWhenCompensationJobCompletes() {
    GitlabTableSyncTask task = task();
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setConfigId(3L);
    job.setJobType(GitlabSyncJobType.COMPENSATION_SCAN);
    job.setStatus(SyncStatus.RUNNING);
    job.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 0));
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L)).thenReturn(config);
    when(jobMapper.selectById(9L)).thenReturn(job);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(any(), any()))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            schema,
            "ods_gitlab_issues",
            true,
            new GitlabMirrorTableRegistry()));
    when(externalDbService.incrementalCursorScan(eq(config), any(), any(), eq(null), eq(null), eq(2)))
        .thenReturn(List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 1, 2, 3, 4, 5))));
    when(storageService.upsertBatch(eq(schema), any(), eq(21L))).thenReturn(new MirrorBatchWriteResult(1, 1, 0));
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any()))
        .thenReturn(0L, 0L, 0L, 1L, 1L);

    service.executeTask(task);

    verify(jobMapper).updateById(job);
    verify(factBuildTaskService).enqueueMirrorRefreshTasks(config, false);
    verify(logService).finishRunningLogsForCompletedJob(
        eq(3L),
        eq(SyncType.COMPENSATION),
        eq(LocalDateTime.of(2026, 1, 2, 3, 0)),
        any(),
        eq(SyncStatus.SUCCESS),
        any());
    assertThat(job.getStatus()).isEqualTo(SyncStatus.SUCCESS);
  }

  @Test
  void shouldMarkJobPartialSuccessWhenSomeTablesFailedAfterRetries() {
    GitlabTableSyncTask task = task();
    GitlabTableSyncState state = state();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setConfigId(3L);
    job.setJobType(GitlabSyncJobType.COMPENSATION_SCAN);
    job.setStatus(SyncStatus.RUNNING);
    task.setRetryCount(3);

    when(stateMapper.selectOne(ArgumentMatchers.<Wrapper<GitlabTableSyncState>>any())).thenReturn(state);
    when(configService.getConfigById(3L))
        .thenThrow(new IllegalStateException("permission denied"))
        .thenReturn(config());
    when(jobMapper.selectById(9L)).thenReturn(job);
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any()))
        .thenReturn(0L, 1L, 0L, 2L, 1L);

    service.executeTask(task);

    verify(taskMapper, times(0)).insert(ArgumentMatchers.<GitlabTableSyncTask>any());
    verify(jobMapper).updateById(job);
    assertThat(task.getStatus()).isEqualTo(SyncStatus.FAILED);
    assertThat(job.getStatus()).isEqualTo(SyncStatus.PARTIAL_SUCCESS);
    assertThat(job.getErrorMessage()).isEqualTo("Some table sync tasks failed or timed out");
    verify(factBuildTaskService).enqueueMirrorRefreshTasks(any(), eq(false));
  }

  private GitlabTableSyncTask task() {
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setId(21L);
    task.setJobId(9L);
    task.setConfigId(3L);
    task.setSourceInstance("default");
    task.setSourceTable("issues");
    task.setMirrorTable("ods_gitlab_issues");
    task.setTaskType(GitlabTableSyncTaskType.COMPENSATION_INCREMENTAL);
    task.setRowStrategy(GitlabTableRowStrategy.INCREMENTAL);
    task.setStatus(SyncStatus.PENDING);
    task.setWatermarkAt(LocalDateTime.of(2026, 1, 1, 0, 0));
    task.setBatchSize(2);
    task.setMaxRetryCount(3);
    return task;
  }

  private GitlabTableSyncState state() {
    GitlabTableSyncState state = new GitlabTableSyncState();
    state.setConfigId(3L);
    state.setSourceInstance("default");
    state.setSourceTable("issues");
    state.setMirrorTable("ods_gitlab_issues");
    state.setPrimaryKeyColumns("id");
    state.setUpdatedAtColumn("updated_at");
    state.setRowStrategy(GitlabTableRowStrategy.INCREMENTAL);
    state.setSyncEnabled(true);
    return state;
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(3L);
    config.setSourceInstance("default");
    config.setSourceMode(SourceMode.DOCKER);
    return config;
  }
}
