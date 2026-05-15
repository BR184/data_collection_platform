package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseBrowserServiceTest {
  private GitlabMirrorTableRegistryMapper registryMapper;
  private GitlabMirrorSyncService syncService;
  private DatabaseBrowserService databaseBrowserService;

  @BeforeEach
  void setUp() {
    registryMapper = mock(GitlabMirrorTableRegistryMapper.class);
    syncService = mock(GitlabMirrorSyncService.class);
    databaseBrowserService =
        new DatabaseBrowserService(
            mock(JdbcTemplate.class),
            registryMapper,
            mock(DatabaseBrowserMirrorTableDefinitionFactory.class),
            syncService);
  }

  @Test
  void shouldReturnRunDetailsForMirrorTableRefresh() {
    GitlabMirrorTableRegistry registry = registry();
    when(registryMapper.selectOne(any())).thenReturn(registry);
    when(syncService.refreshTablesOnDemandDetailed(1L, List.of("issues"), "database-browser:ods_gitlab_issues"))
        .thenReturn(new GitlabMirrorSyncService.OnDemandRefreshResult(
            88L,
            List.of("issues"),
            1,
            List.of(),
            SyncStatus.QUEUED,
            "queued"));

    GitlabMirrorSyncService.OnDemandRefreshResult result =
        databaseBrowserService.refreshTableDetailed("ods_gitlab_issues");

    verify(syncService).refreshTablesOnDemandDetailed(1L, List.of("issues"), "database-browser:ods_gitlab_issues");
    assertThat(result.jobId()).isEqualTo(88L);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.message()).isEqualTo("queued");
  }

  private GitlabMirrorTableRegistry registry() {
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setConfigId(1L);
    registry.setSourceTableName("issues");
    registry.setMirrorTableName("ods_gitlab_issues");
    registry.setInitialized(true);
    return registry;
  }
}
