package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GitlabTableSyncPlanningServiceTest {
  private GitlabSyncJobMapper jobMapper;
  private GitlabTableSyncStateMapper stateMapper;
  private GitlabTableSyncTaskMapper taskMapper;
  private GitlabMirrorTableRegistryMapper registryMapper;
  private GitlabTableSyncPlanningService service;

  @BeforeEach
  void setUp() {
    jobMapper = mock(GitlabSyncJobMapper.class);
    stateMapper = mock(GitlabTableSyncStateMapper.class);
    taskMapper = mock(GitlabTableSyncTaskMapper.class);
    registryMapper = mock(GitlabMirrorTableRegistryMapper.class);
    service = new GitlabTableSyncPlanningService(jobMapper, stateMapper, taskMapper, registryMapper);
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
}
