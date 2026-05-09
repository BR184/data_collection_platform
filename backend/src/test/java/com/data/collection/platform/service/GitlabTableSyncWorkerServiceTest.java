package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class GitlabTableSyncWorkerServiceTest {
  private GitlabTableSyncTaskMapper taskMapper;
  private GitlabTableSyncStateMapper stateMapper;
  private GitlabSyncJobMapper jobMapper;
  private GitlabConfigService configService;
  private GitlabExternalDbService externalDbService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabMirrorTableStorageService storageService;
  private FactBuildTaskService factBuildTaskService;
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
    service = new GitlabTableSyncWorkerService(
        taskMapper,
        stateMapper,
        jobMapper,
        configService,
        externalDbService,
        mirrorSchemaService,
        storageService,
        new GitlabMirrorProperties(),
        factBuildTaskService);
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
    assertThat(staleTask.getStatus()).isEqualTo(SyncStatus.TIMEOUT);
    assertThat(state.isDirtyFlag()).isTrue();
    assertThat(state.getLastError()).isEqualTo("Table sync task lease timed out");

    ArgumentCaptor<GitlabTableSyncTask> retryCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(retryCaptor.capture());
    GitlabTableSyncTask retryTask = retryCaptor.getValue();
    assertThat(retryTask.getStatus()).isEqualTo(SyncStatus.PENDING);
    assertThat(retryTask.getRetryCount()).isEqualTo(2);
    assertThat(retryTask.getSourceTable()).isEqualTo("issues");
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
  void shouldEnqueueFactRefreshWhenCompensationJobCompletes() {
    GitlabTableSyncTask task = task();
    GitlabTableSyncState state = state();
    GitlabSyncConfig config = config();
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(9L);
    job.setConfigId(3L);
    job.setJobType(GitlabSyncJobType.COMPENSATION_SCAN);
    job.setStatus(SyncStatus.RUNNING);
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
    when(taskMapper.selectCount(ArgumentMatchers.<Wrapper<GitlabTableSyncTask>>any())).thenReturn(0L);

    service.executeTask(task);

    verify(jobMapper).updateById(job);
    verify(factBuildTaskService).enqueueMirrorRefreshTasks(config, false);
    assertThat(job.getStatus()).isEqualTo(SyncStatus.SUCCESS);
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
