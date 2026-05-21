package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseBrowserServiceTest {
  private GitlabMirrorTableRegistryMapper registryMapper;
  private GitlabMirrorSyncService syncService;
  private GitlabConfigService configService;
  private SourceMetadataInspector sourceMetadataInspector;
  private GitlabExternalDbService externalDbService;
  private DatabaseBrowserService databaseBrowserService;

  @BeforeEach
  void setUp() {
    registryMapper = mock(GitlabMirrorTableRegistryMapper.class);
    syncService = mock(GitlabMirrorSyncService.class);
    configService = mock(GitlabConfigService.class);
    sourceMetadataInspector = mock(SourceMetadataInspector.class);
    externalDbService = mock(GitlabExternalDbService.class);
    databaseBrowserService =
        new DatabaseBrowserService(
            mock(JdbcTemplate.class),
            registryMapper,
            mock(DatabaseBrowserMirrorTableDefinitionFactory.class),
            syncService,
            configService,
            sourceMetadataInspector,
            externalDbService);
  }

  @Test
  void shouldReturnRunDetailsForMirrorTableRefresh() {
    GitlabMirrorTableRegistry registry = registry();
    registry.setLastSyncTime(java.time.LocalDateTime.of(2026, 5, 21, 14, 0));
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

  @Test
  void shouldExposeMirrorAndSourceTableOptionsFromInitializedRegistry() {
    GitlabMirrorTableRegistry registry = registry();
    registry.setLastSyncTime(java.time.LocalDateTime.of(2026, 5, 18, 10, 0));
    registry.setSyncStatus("SUCCESS");
    DatabaseBrowserMirrorTableDefinitionFactory factory = mock(DatabaseBrowserMirrorTableDefinitionFactory.class);
    when(factory.buildMirrorLabel("issues")).thenReturn("镜像表 / issues");
    when(registryMapper.selectList(any())).thenReturn(List.of(registry));
    databaseBrowserService =
        new DatabaseBrowserService(
            mock(JdbcTemplate.class),
            registryMapper,
            factory,
            syncService,
            configService,
            sourceMetadataInspector,
            externalDbService);

    var options = databaseBrowserService.listTables();

    assertThat(options)
        .anySatisfy(option -> {
          assertThat(option.getTableName()).isEqualTo("ods_gitlab_issues");
          assertThat(option.getTableKind()).isEqualTo("MIRROR");
          assertThat(option.isRefreshable()).isTrue();
        })
        .anySatisfy(option -> {
          assertThat(option.getTableName()).isEqualTo("source:1:issues");
          assertThat(option.getLabel()).isEqualTo("来源表 / issues / config 1");
          assertThat(option.getTableKind()).isEqualTo("SOURCE");
          assertThat(option.isRefreshable()).isFalse();
        });
  }

  @Test
  void shouldExposeSyncRunTablesForOperationalTroubleshooting() {
    when(registryMapper.selectList(any())).thenReturn(List.of());

    var options = databaseBrowserService.listTables();

    assertThat(options)
        .anySatisfy(option -> {
          assertThat(option.getTableName()).isEqualTo("sync_runs");
          assertThat(option.getLabel()).isEqualTo("同步运行");
          assertThat(option.isRefreshable()).isFalse();
        })
        .anySatisfy(option -> assertThat(option.getTableName()).isEqualTo("sync_run_events"))
        .anySatisfy(option -> assertThat(option.getTableName()).isEqualTo("sync_run_table_tasks"));
  }

  @Test
  void shouldDisableMirrorRefreshUntilFullSyncBaselineExists() {
    GitlabMirrorTableRegistry registry = registry();
    registry.setLastSyncTime(null);
    registry.setSyncStatus("IDLE");
    DatabaseBrowserMirrorTableDefinitionFactory factory = mock(DatabaseBrowserMirrorTableDefinitionFactory.class);
    when(factory.buildMirrorLabel("issues")).thenReturn("镜像表 / issues");
    when(registryMapper.selectList(any())).thenReturn(List.of(registry));
    databaseBrowserService =
        new DatabaseBrowserService(
            mock(JdbcTemplate.class),
            registryMapper,
            factory,
            syncService,
            configService,
            sourceMetadataInspector,
            externalDbService);

    var options = databaseBrowserService.listTables();

    assertThat(options)
        .anySatisfy(option -> {
          assertThat(option.getTableName()).isEqualTo("ods_gitlab_issues");
          assertThat(option.isRefreshable()).isFalse();
        });
  }

  @Test
  void shouldPreviewSourceTableWithoutCountingAllRows() {
    GitlabMirrorTableRegistry registry = registry();
    registry.setPrimaryKeyColumns("id");
    registry.setUpdatedAtColumn("updated_at");
    when(registryMapper.selectOne(any())).thenReturn(registry);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceEnabled(true);
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("127.0.0.1");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("secret");
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    SourceTableSchema schema = new SourceTableSchema(
        "issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "character varying", true, 2),
            new SourceTableColumn("updated_at", "timestamp", true, 3)));
    when(sourceMetadataInspector.discoverTableSchema(any(), any())).thenReturn(schema);
    when(externalDbService.previewTablePage(any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(List.of(Map.of("id", 1L, "title", "issue A")));

    var response = databaseBrowserService.getTableRows("source:1:issues", 1, 20, "issue", "updated_at", "desc");

    assertThat(response.getTableKind()).isEqualTo("SOURCE");
    assertThat(response.isRefreshable()).isFalse();
    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getStatusMessage()).contains("不执行全表 count");
    verify(externalDbService)
        .previewTablePage(any(), any(), any(), org.mockito.ArgumentMatchers.eq("issue"), org.mockito.ArgumentMatchers.eq("updated_at"), org.mockito.ArgumentMatchers.eq("desc"), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(20));
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
