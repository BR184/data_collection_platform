package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeWorkspaceServiceTest {

  @Mock private GitlabConfigService configService;
  @Mock private GitlabTableSyncPlanningService tableSyncPlanningService;
  @Mock private FactBuildTaskService factBuildTaskService;

  private RealtimeWorkspaceService service;

  @BeforeEach
  void setUp() {
    service = new RealtimeWorkspaceService(
        configService,
        tableSyncPlanningService,
        factBuildTaskService,
        null);
  }

  @Test
  void shouldBuildStatusFromPersistedManualRefreshJobWhenMemoryStateIsMissing() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 5, 13, 9, 0);
    LocalDateTime finishedAt = LocalDateTime.of(2026, 5, 13, 9, 2);
    when(configService.getConfig()).thenReturn(config(1L));
    when(tableSyncPlanningService.resolveLatestActivityAt(1L)).thenReturn(finishedAt);
    when(tableSyncPlanningService.findLatestManualRefreshJobByReason("system-test-defect-summary"))
        .thenReturn(job(31L, "default", SyncStatus.SUCCESS, createdAt, finishedAt));
    when(tableSyncPlanningService.listTasksForJob(31L))
        .thenReturn(List.of(task("issues"), task("projects")));
    when(factBuildTaskService.latest("issue"))
        .thenReturn(factTask("issue", "SUCCESS", createdAt.plusMinutes(1), finishedAt));

    RealtimeWorkspaceStatusResponse response = service.getStatus("system-test-defect-summary");

    assertThat(response.status()).isEqualTo("READY");
    assertThat(response.refreshing()).isFalse();
    assertThat(response.jobId()).isEqualTo(31L);
    assertThat(response.sourceTables()).containsExactly("issues", "projects");
    assertThat(response.plannedTasks()).isEqualTo(2);
    assertThat(response.mirrorStatus()).isEqualTo("SUCCESS");
    assertThat(response.factStatus()).isEqualTo("SUCCESS");
    assertThat(response.lastSyncedAt()).isEqualTo(finishedAt);
  }

  @Test
  void shouldReportFactFailureFromPersistedFactTask() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 5, 13, 10, 0);
    when(configService.getConfig()).thenReturn(config(1L));
    when(tableSyncPlanningService.findLatestManualRefreshJobByReason("system-test-defect-summary"))
        .thenReturn(job(32L, "default", SyncStatus.SUCCESS, createdAt, createdAt.plusMinutes(1)));
    when(tableSyncPlanningService.listTasksForJob(32L))
        .thenReturn(List.of(task("issues")));
    when(factBuildTaskService.latest("issue"))
        .thenReturn(factTask("issue", "FAILED", createdAt.plusMinutes(1), createdAt.plusMinutes(2)));

    RealtimeWorkspaceStatusResponse response = service.getStatus("system-test-defect-summary");

    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.message()).contains("事实数据刷新失败");
    assertThat(response.mirrorStatus()).isEqualTo("SUCCESS");
    assertThat(response.factStatus()).isEqualTo("FAILED");
  }

  @Test
  void shouldUseMergeRequestFactScopeForCodeReviewWorkspace() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 5, 13, 11, 0);
    when(configService.getConfig()).thenReturn(config(1L));
    when(tableSyncPlanningService.findLatestManualRefreshJobByReason("code-review-illegal-records"))
        .thenReturn(job(33L, "default", SyncStatus.SUCCESS, createdAt, createdAt.plusMinutes(1)));
    when(tableSyncPlanningService.listTasksForJob(33L))
        .thenReturn(List.of(task("merge_requests")));
    when(factBuildTaskService.latest("merge-request"))
        .thenReturn(factTask("merge-request", "SUCCESS", createdAt.plusMinutes(1), createdAt.plusMinutes(2)));

    RealtimeWorkspaceStatusResponse response = service.getStatus("code-review-illegal-records");

    assertThat(response.factStatus()).isEqualTo("SUCCESS");
    verify(factBuildTaskService).latest("merge-request");
  }

  private GitlabSyncConfig config(Long id) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(id);
    return config;
  }

  private GitlabSyncJob job(
      Long id,
      String sourceInstance,
      SyncStatus status,
      LocalDateTime createdAt,
      LocalDateTime finishedAt) {
    GitlabSyncJob job = new GitlabSyncJob();
    job.setId(id);
    job.setSourceInstance(sourceInstance);
    job.setStatus(status);
    job.setCreatedAt(createdAt);
    job.setStartedAt(createdAt);
    job.setFinishedAt(finishedAt);
    return job;
  }

  private GitlabTableSyncTask task(String sourceTable) {
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setSourceTable(sourceTable);
    return task;
  }

  private FactBuildTaskResponse factTask(
      String scope,
      String status,
      LocalDateTime createdAt,
      LocalDateTime finishedAt) {
    return new FactBuildTaskResponse(
        1L,
        "run-1",
        scope,
        false,
        status,
        "MANUAL",
        null,
        0,
        status,
        null,
        createdAt,
        finishedAt,
        createdAt,
        finishedAt);
  }
}
