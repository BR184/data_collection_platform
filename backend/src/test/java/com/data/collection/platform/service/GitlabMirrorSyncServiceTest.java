package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabMirrorSyncServiceTest {
  private GitlabConfigService configService;
  private GitlabExternalDbService externalDbService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabMirrorTableStorageService mirrorTableStorageService;
  private GitlabMirrorRecordMapper mirrorRecordMapper;
  private GitlabSystemHookPreciseSyncPlanner systemHookPreciseSyncPlanner;
  private FactBuildTaskService factBuildTaskService;
  private SyncRunSubmissionService syncRunSubmissionService;
  private GitlabMirrorSyncService syncService;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    externalDbService = mock(GitlabExternalDbService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    mirrorTableStorageService = mock(GitlabMirrorTableStorageService.class);
    mirrorRecordMapper = mock(GitlabMirrorRecordMapper.class);
    systemHookPreciseSyncPlanner = mock(GitlabSystemHookPreciseSyncPlanner.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    syncRunSubmissionService = mock(SyncRunSubmissionService.class);
    syncService =
        new GitlabMirrorSyncService(
            configService,
            externalDbService,
            mirrorSchemaService,
            mirrorTableStorageService,
            mirrorRecordMapper,
            systemHookPreciseSyncPlanner,
            factBuildTaskService,
            syncRunSubmissionService);
  }

  @Test
  void shouldSubmitFullSyncThroughUnifiedRunService() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    SyncRunSubmissionResult result =
        new SyncRunSubmissionResult(88L, SyncType.FULL, SyncStatus.QUEUED, com.data.collection.platform.entity.SyncSubmissionAction.QUEUED, null, "queued");
    when(syncRunSubmissionService.submitFullSync(config, "Manual full sync")).thenReturn(result);

    SyncRunSubmissionResult actual = syncService.startFullSync();

    verify(syncRunSubmissionService).submitFullSync(config, "Manual full sync");
    assertThat(actual).isSameAs(result);
  }

  @Test
  void shouldMergeOnDemandRefreshIntoQueuedMessageFlow() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    when(syncRunSubmissionService.submitTableRefresh(config, List.of("issues"), "manual refresh"))
        .thenReturn(
            new SyncRunSubmissionResult(
                99L,
                SyncType.INCREMENTAL,
                SyncStatus.QUEUED,
                com.data.collection.platform.entity.SyncSubmissionAction.QUEUED,
                null,
                "queued"));

    GitlabMirrorSyncService.OnDemandRefreshResult result =
        syncService.refreshTablesOnDemandDetailed(List.of("Issues"), "manual refresh");

    verify(syncRunSubmissionService).submitTableRefresh(config, List.of("issues"), "manual refresh");
    assertThat(result.jobId()).isEqualTo(99L);
    assertThat(result.plannedTasks()).isEqualTo(1);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    return config;
  }
}
