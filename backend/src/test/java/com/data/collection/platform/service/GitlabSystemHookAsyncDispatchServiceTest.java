package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSystemHookAsyncDispatchServiceTest {
  private GitlabConfigService configService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabWhitelistService whitelistService;
  private GitlabTableSyncPlanningService tableSyncPlanningService;
  private GitlabSystemHookAsyncDispatchService service;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    service = new GitlabSystemHookAsyncDispatchService(
        configService,
        mirrorSchemaService,
        whitelistService,
        tableSyncPlanningService);
  }

  @Test
  void shouldTreatSystemHookAsWakeupAndQueueCompensationPlan() {
    GitlabSyncConfig config = baseConfig();
    Map<String, Object> payload = Map.of("object_kind", "issue");
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.markIncrementalTablesDirty(config, tables, "System Hook dirty signal: Issue Hook"))
        .thenReturn(1);
    when(tableSyncPlanningService.createCompensationScanPlan(config, tables))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(19L, 1, 1, 0));

    service.accept(config, "Issue Hook", payload);

    verify(whitelistService).resolveOptions(config);
    verify(tableSyncPlanningService).markIncrementalTablesDirty(eq(config), eq(tables), eq("System Hook dirty signal: Issue Hook"));
    verify(tableSyncPlanningService).createCompensationScanPlan(eq(config), eq(tables));
  }

  @Test
  void flushShouldOnlyRecoverStaleSchemaStatuses() {
    service.flushPending();

    verify(mirrorSchemaService).recoverStaleSyncingStatuses();
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    return config;
  }
}
