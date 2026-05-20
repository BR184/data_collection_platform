package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.service.sync.SyncRunLeaseService;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.service.sync.SyncRunTableWorkerService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabMirrorSyncServiceTest {
  private GitlabConfigService configService;
  private SourceConnectionTester sourceConnectionTester;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private SyncRunSubmissionService syncRunSubmissionService;
  private SyncRunLeaseService syncRunLeaseService;
  private SyncRunTableWorkerService syncRunTableWorkerService;
  private GitlabMirrorTableRegistryMapper registryMapper;
  private SyncRunTableStateMapper tableStateMapper;
  private GitlabMirrorSyncService syncService;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    sourceConnectionTester = mock(SourceConnectionTester.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    syncRunSubmissionService = mock(SyncRunSubmissionService.class);
    syncRunLeaseService = mock(SyncRunLeaseService.class);
    syncRunTableWorkerService = mock(SyncRunTableWorkerService.class);
    registryMapper = mock(GitlabMirrorTableRegistryMapper.class);
    tableStateMapper = mock(SyncRunTableStateMapper.class);
    syncService =
        new GitlabMirrorSyncService(
            configService,
            sourceConnectionTester,
            mirrorSchemaService,
            syncRunSubmissionService,
            syncRunLeaseService,
            syncRunTableWorkerService,
            registryMapper,
            tableStateMapper);
  }

  @Test
  void shouldRouteConnectionTestThroughSourceConnectionTester() {
    GitlabSyncConfig config = config();
    when(configService.getConfigById(1L)).thenReturn(config);

    syncService.testConnection(1L);

    verify(sourceConnectionTester).testConnection(config);
  }

  @Test
  void shouldRecoverTimedOutTableTasksThroughUnifiedWorker() {
    when(syncRunTableWorkerService.recoverTimedOutTasks()).thenReturn(3);

    syncService.recoverTimedOutTasks();

    verify(mirrorSchemaService).recoverStaleSyncingStatuses();
    verify(syncRunLeaseService).recoverTimedOutRuns();
    verify(syncRunTableWorkerService).recoverTimedOutTasks();
  }

  @Test
  void shouldSubmitFullSyncThroughUnifiedRunService() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    SyncRunSubmissionResult result =
        new SyncRunSubmissionResult(88L, SyncType.FULL, SyncStatus.QUEUED, com.data.collection.platform.entity.SyncSubmissionAction.QUEUED, null, "queued");
    when(syncRunSubmissionService.submitFullSync(config, "手动全量同步")).thenReturn(result);

    SyncRunSubmissionResult actual = syncService.startFullSync();

    verify(syncRunSubmissionService).submitFullSync(config, "手动全量同步");
    assertThat(actual).isSameAs(result);
  }

  @Test
  void shouldMergeOnDemandRefreshIntoQueuedMessageFlow() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    when(registryMapper.selectOne(any())).thenReturn(registry("issues", "id", "updated_at"));
    when(tableStateMapper.selectOne(any())).thenReturn(tableState("issues", LocalDateTime.of(2026, 5, 15, 10, 0)));
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
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.message()).isEqualTo("queued");
  }

  @Test
  void shouldRejectTableRefreshOutsideMirrorRegistry() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    when(registryMapper.selectOne(any())).thenReturn(null);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.refreshTablesOnDemandDetailed(List.of("issues"), "manual refresh"))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class)
        .hasMessageContaining("源表未加入镜像白名单");
  }

  @Test
  void shouldRejectTableRefreshWithoutUpdatedAtColumn() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    when(registryMapper.selectOne(any())).thenReturn(registry("issues", "id", null));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.refreshTablesOnDemandDetailed(List.of("issues"), "manual refresh"))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class)
        .hasMessageContaining("updated_at");
  }

  @Test
  void shouldRejectTableRefreshWithoutBaselineWatermark() {
    GitlabSyncConfig config = config();
    when(configService.getConfig()).thenReturn(config);
    when(registryMapper.selectOne(any())).thenReturn(registry("issues", "id", "updated_at"));
    when(tableStateMapper.selectOne(any())).thenReturn(tableState("issues", null));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.refreshTablesOnDemandDetailed(List.of("issues"), "manual refresh"))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class)
        .hasMessageContaining("全量同步基线");
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

  private GitlabMirrorTableRegistry registry(String sourceTable, String primaryKeyColumns, String updatedAtColumn) {
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setConfigId(1L);
    registry.setSourceTableName(sourceTable);
    registry.setMirrorTableName("ods_gitlab_" + sourceTable);
    registry.setInitialized(true);
    registry.setPrimaryKeyColumns(primaryKeyColumns);
    registry.setUpdatedAtColumn(updatedAtColumn);
    return registry;
  }

  private SyncRunTableState tableState(String sourceTable, LocalDateTime watermark) {
    SyncRunTableState state = new SyncRunTableState();
    state.setConfigId(1L);
    state.setSourceInstance("alpha");
    state.setSourceTable(sourceTable);
    state.setMirrorTable("ods_gitlab_" + sourceTable);
    state.setPrimaryKeyColumns("id");
    state.setUpdatedAtColumn("updated_at");
    state.setLastWatermarkAt(watermark);
    return state;
  }
}
