package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GitlabTableSyncPlanningServiceTest {
  private GitlabSyncJobMapper jobMapper;
  private GitlabTableSyncStateMapper stateMapper;
  private GitlabTableSyncTaskMapper taskMapper;
  private GitlabMirrorTableRegistryMapper registryMapper;
  private GitlabExternalDbService externalDbService;
  private GitlabTableSyncPlanningService service;

  @BeforeEach
  void setUp() {
    jobMapper = mock(GitlabSyncJobMapper.class);
    stateMapper = mock(GitlabTableSyncStateMapper.class);
    taskMapper = mock(GitlabTableSyncTaskMapper.class);
    registryMapper = mock(GitlabMirrorTableRegistryMapper.class);
    externalDbService = mock(GitlabExternalDbService.class);
    service = new GitlabTableSyncPlanningService(jobMapper, stateMapper, taskMapper, registryMapper, externalDbService);
  }

  @Test
  void shouldPlanIncrementalTasksAndDeferTablesWithoutUpdatedAtToVerification() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(3L);
    config.setSourceInstance("corp-main");
    when(jobMapper.insert(org.mockito.ArgumentMatchers.<GitlabSyncJob>any())).thenAnswer(invocation -> {
      GitlabSyncJob job = invocation.getArgument(0);
      job.setId(77L);
      return 1;
    });

    GitlabTableSyncPlanningService.CompensationPlanResult result = service.createCompensationScanPlan(
        config,
        List.of(
            new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
            new TableWhitelistOption("label_links", "Label links", "id", null, true)));

    assertThat(result.jobId()).isEqualTo(77L);
    assertThat(result.discoveredTables()).isEqualTo(2);
    assertThat(result.plannedTasks()).isEqualTo(1);
    assertThat(result.verifyOnlyTables()).isEqualTo(1);

    ArgumentCaptor<GitlabTableSyncState> stateCaptor = ArgumentCaptor.forClass(GitlabTableSyncState.class);
    verify(stateMapper, times(2)).insert(stateCaptor.capture());
    assertThat(stateCaptor.getAllValues())
        .extracting(GitlabTableSyncState::getSourceTable)
        .containsExactly("issues", "label_links");
    assertThat(stateCaptor.getAllValues().get(0).getRowStrategy()).isEqualTo(GitlabTableRowStrategy.INCREMENTAL);
    assertThat(stateCaptor.getAllValues().get(0).isSyncEnabled()).isTrue();
    assertThat(stateCaptor.getAllValues().get(1).getRowStrategy()).isEqualTo(GitlabTableRowStrategy.VERIFY_ONLY);
    assertThat(stateCaptor.getAllValues().get(1).isSyncEnabled()).isFalse();

    ArgumentCaptor<GitlabTableSyncTask> taskCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    GitlabTableSyncTask task = taskCaptor.getValue();
    assertThat(task.getJobId()).isEqualTo(77L);
    assertThat(task.getConfigId()).isEqualTo(3L);
    assertThat(task.getSourceInstance()).isEqualTo("corp_main");
    assertThat(task.getSourceTable()).isEqualTo("issues");
    assertThat(task.getStatus()).isEqualTo(SyncStatus.PENDING);
    assertThat(task.getBatchSize()).isEqualTo(500);
  }

  @Test
  void shouldPlanDailyVerificationTasksForAllWhitelistedTables() {
    GitlabSyncConfig config = config();
    when(jobMapper.insert(org.mockito.ArgumentMatchers.<GitlabSyncJob>any())).thenAnswer(invocation -> {
      GitlabSyncJob job = invocation.getArgument(0);
      job.setId(88L);
      return 1;
    });

    GitlabTableSyncPlanningService.CompensationPlanResult result = service.createDailyVerificationPlan(
        config,
        List.of(
            new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
            new TableWhitelistOption("label_links", "Label links", "id", null, true)));

    assertThat(result.jobId()).isEqualTo(88L);
    assertThat(result.plannedTasks()).isEqualTo(2);
    assertThat(result.verifyOnlyTables()).isEqualTo(1);

    ArgumentCaptor<GitlabSyncJob> jobCaptor = ArgumentCaptor.forClass(GitlabSyncJob.class);
    verify(jobMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getJobType()).isEqualTo(GitlabSyncJobType.DAILY_VERIFY);
    assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(SyncTriggerType.SCHEDULE);

    ArgumentCaptor<GitlabTableSyncTask> taskCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(GitlabTableSyncTask::getTaskType)
        .containsExactly(GitlabTableSyncTaskType.DAILY_VERIFY, GitlabTableSyncTaskType.DAILY_VERIFY);
  }

  @Test
  void shouldPlanManualRefreshOnlyForRequestedIncrementalTables() {
    GitlabSyncConfig config = config();
    when(jobMapper.insert(org.mockito.ArgumentMatchers.<GitlabSyncJob>any())).thenAnswer(invocation -> {
      GitlabSyncJob job = invocation.getArgument(0);
      job.setId(89L);
      return 1;
    });

    GitlabTableSyncPlanningService.CompensationPlanResult result = service.createManualRefreshPlan(
        config,
        List.of(
            new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
            new TableWhitelistOption("label_links", "Label links", "id", null, true)),
        List.of("issues"),
        "board-refresh");

    assertThat(result.jobId()).isEqualTo(89L);
    assertThat(result.discoveredTables()).isEqualTo(1);
    assertThat(result.plannedTasks()).isEqualTo(1);

    ArgumentCaptor<GitlabSyncJob> jobCaptor = ArgumentCaptor.forClass(GitlabSyncJob.class);
    verify(jobMapper).insert(jobCaptor.capture());
    assertThat(jobCaptor.getValue().getJobType()).isEqualTo(GitlabSyncJobType.MANUAL_REFRESH);
    assertThat(jobCaptor.getValue().getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(jobCaptor.getValue().getPriority()).isEqualTo(100);

    ArgumentCaptor<GitlabTableSyncTask> taskCaptor = ArgumentCaptor.forClass(GitlabTableSyncTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getTaskType()).isEqualTo(GitlabTableSyncTaskType.MANUAL_REFRESH);
    assertThat(taskCaptor.getValue().getSourceTable()).isEqualTo("issues");
  }

  @Test
  void shouldSkipCleanCompensationTableWhenSourceWatermarkDidNotAdvance() {
    GitlabSyncConfig config = config();
    GitlabTableSyncState existingState = new GitlabTableSyncState();
    existingState.setId(9L);
    existingState.setConfigId(3L);
    existingState.setSourceInstance("corp_main");
    existingState.setSourceTable("issues");
    existingState.setLastWatermarkAt(LocalDateTime.of(2026, 5, 9, 10, 0));
    existingState.setDirtyFlag(false);

    when(stateMapper.selectOne(any())).thenReturn(existingState);
    when(externalDbService.findMaxUpdatedAt(
            config,
            new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)))
        .thenReturn(LocalDateTime.of(2026, 5, 9, 10, 0));
    when(jobMapper.insert(org.mockito.ArgumentMatchers.<GitlabSyncJob>any())).thenAnswer(invocation -> {
      GitlabSyncJob job = invocation.getArgument(0);
      job.setId(90L);
      return 1;
    });

    GitlabTableSyncPlanningService.CompensationPlanResult result = service.createCompensationScanPlan(
        config,
        List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)));

    assertThat(result.jobId()).isEqualTo(90L);
    assertThat(result.plannedTasks()).isZero();
    verify(taskMapper, times(0)).insert(org.mockito.ArgumentMatchers.<GitlabTableSyncTask>any());
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(3L);
    config.setSourceInstance("corp-main");
    return config;
  }
}
