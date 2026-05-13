package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabTableSyncDiagnosticsServiceTest {

  @Test
  void shouldReturnTableStatesWithLatestTaskAndTaskCounters() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabTableSyncStateMapper stateMapper = mock(GitlabTableSyncStateMapper.class);
    GitlabTableSyncTaskMapper taskMapper = mock(GitlabTableSyncTaskMapper.class);
    GitlabTableSyncDiagnosticsService service =
        new GitlabTableSyncDiagnosticsService(configService, stateMapper, taskMapper);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(7L);
    config.setSourceInstance("corp");
    config.setSourceMode(SourceMode.DIRECT);
    GitlabTableSyncState issues = state("issues", true);
    GitlabTableSyncState projects = state("projects", false);
    GitlabTableSyncTask latestIssueTask = task("issues", GitlabTableSyncTaskType.SHARD_REPAIR, SyncStatus.RETRYING);
    GitlabTableSyncTask olderIssueTask = task("issues", GitlabTableSyncTaskType.DAILY_VERIFY, SyncStatus.SUCCESS);
    GitlabTableSyncTask projectTask = task("projects", GitlabTableSyncTaskType.COMPENSATION_INCREMENTAL, SyncStatus.SUCCESS);

    when(configService.getConfigById(7L)).thenReturn(config);
    when(stateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(issues, projects));
    when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(latestIssueTask, olderIssueTask, projectTask));
    when(taskMapper.selectCount(any(Wrapper.class))).thenReturn(2L, 1L, 1L, 0L, 0L);

    GitlabTableSyncDiagnosticsResponse response = service.diagnose(7L);

    assertThat(response.configId()).isEqualTo(7L);
    assertThat(response.sourceInstance()).isEqualTo("corp");
    assertThat(response.tableCount()).isEqualTo(2);
    assertThat(response.dirtyTableCount()).isEqualTo(1);
    assertThat(response.pendingTaskCount()).isEqualTo(2L);
    assertThat(response.runningTaskCount()).isEqualTo(1L);
    assertThat(response.retryingTaskCount()).isEqualTo(1L);
    assertThat(response.tables()).hasSize(2);
    assertThat(response.tables().get(0).sourceTable()).isEqualTo("issues");
    assertThat(response.tables().get(0).dirty()).isTrue();
    assertThat(response.tables().get(0).latestTaskType()).isEqualTo(GitlabTableSyncTaskType.SHARD_REPAIR);
    assertThat(response.tables().get(0).latestTaskStatus()).isEqualTo(SyncStatus.RETRYING);
    assertThat(response.tables().get(0).latestTaskError()).isEqualTo("network timeout");
  }

  @Test
  void shouldBuildProgressFromTableLevelTasks() {
    GitlabConfigService configService = mock(GitlabConfigService.class);
    GitlabTableSyncStateMapper stateMapper = mock(GitlabTableSyncStateMapper.class);
    GitlabTableSyncTaskMapper taskMapper = mock(GitlabTableSyncTaskMapper.class);
    GitlabTableSyncDiagnosticsService service =
        new GitlabTableSyncDiagnosticsService(configService, stateMapper, taskMapper);
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(31L);
    job.setJobType(GitlabSyncJobType.DAILY_VERIFY);
    job.setStartedAt(LocalDateTime.of(2026, 5, 13, 10, 0));
    GitlabTableSyncTask done = task("issues", GitlabTableSyncTaskType.DAILY_VERIFY, SyncStatus.SUCCESS);
    done.setRowsApplied(10L);
    GitlabTableSyncTask running = task("notes", GitlabTableSyncTaskType.DAILY_VERIFY, SyncStatus.RUNNING);
    running.setRowsApplied(3L);
    GitlabTableSyncTask queuedContinuation = task("notes", GitlabTableSyncTaskType.SHARD_REPAIR, SyncStatus.PENDING);
    queuedContinuation.setRowsApplied(0L);

    when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(done, running, queuedContinuation));

    SyncProgress progress = service.buildProgress(job);

    assertThat(progress.getPhase()).isEqualTo("FULL_SYNC");
    assertThat(progress.getTotalTables()).isEqualTo(2);
    assertThat(progress.getCompletedTables()).isEqualTo(1);
    assertThat(progress.getCurrentTable()).isEqualTo("notes");
    assertThat(progress.getSyncedRecords()).isEqualTo(13);
    assertThat(progress.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 5, 13, 10, 0));
  }

  private GitlabTableSyncState state(String sourceTable, boolean dirty) {
    GitlabTableSyncState state = new GitlabTableSyncState();
    state.setConfigId(7L);
    state.setSourceInstance("corp");
    state.setSourceTable(sourceTable);
    state.setMirrorTable("ods_gitlab_" + sourceTable + "_corp");
    state.setPrimaryKeyColumns("id");
    state.setUpdatedAtColumn("updated_at");
    state.setRowStrategy(GitlabTableRowStrategy.INCREMENTAL);
    state.setSyncEnabled(true);
    state.setDirtyFlag(dirty);
    state.setLastSuccessAt(LocalDateTime.of(2026, 5, 9, 9, 0));
    state.setLastFullVerifiedAt(LocalDateTime.of(2026, 5, 9, 8, 0));
    state.setLastWatermarkAt(LocalDateTime.of(2026, 5, 9, 7, 0));
    state.setSourceRowCount(10L);
    state.setMirrorRowCount(dirty ? 9L : 10L);
    state.setLastError(dirty ? "Daily verification found drift" : "");
    state.setRetryCount(dirty ? 1 : 0);
    return state;
  }

  private GitlabTableSyncTask task(
      String sourceTable,
      GitlabTableSyncTaskType taskType,
      SyncStatus status) {
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setConfigId(7L);
    task.setSourceInstance("corp");
    task.setSourceTable(sourceTable);
    task.setMirrorTable("ods_gitlab_" + sourceTable + "_corp");
    task.setTaskType(taskType);
    task.setStatus(status);
    task.setRunAfter(LocalDateTime.of(2026, 5, 9, 10, 0));
    task.setHeartbeatAt(LocalDateTime.of(2026, 5, 9, 10, 1));
    task.setLeaseUntil(LocalDateTime.of(2026, 5, 9, 10, 5));
    task.setRowsScanned(100L);
    task.setRowsApplied(99L);
    task.setLastError(status == SyncStatus.RETRYING ? "network timeout" : "");
    return task;
  }
}
