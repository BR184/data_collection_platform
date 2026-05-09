package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.database.DatabaseTableColumn;
import com.data.collection.platform.entity.database.DatabaseTableOption;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class DatabaseBrowserServiceTest {

  @Mock
  private JdbcTemplate jdbcTemplate;

  @Mock
  private GitlabMirrorTableRegistryMapper registryMapper;

  @Mock
  private DatabaseBrowserMirrorTableDefinitionFactory mirrorTableDefinitionFactory;

  @Mock
  private GitlabMirrorSyncService gitlabMirrorSyncService;

  private DatabaseBrowserService service;

  @BeforeEach
  void setUp() {
    service =
        new DatabaseBrowserService(
            jdbcTemplate,
            registryMapper,
            mirrorTableDefinitionFactory,
            gitlabMirrorSyncService);
  }

  @Test
  void shouldListSystemAndMirrorTables() {
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setInitialized(true);
    registry.setMirrorTableName("ods_gitlab_issues");
    registry.setSourceTableName("issues");
    registry.setSyncStatus("IDLE");
    registry.setLastSyncTime(LocalDateTime.of(2026, 4, 9, 10, 0));
    when(registryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(registry));
    when(mirrorTableDefinitionFactory.buildMirrorLabel("issues")).thenReturn("镜像表/ issues");

    List<DatabaseTableOption> tables = service.listTables();

    assertThat(tables).extracting(DatabaseTableOption::getTableName).contains("gitlab_sync_logs", "ods_gitlab_issues");
    assertThat(tables.stream()
            .filter(option -> option.getTableName().equals("ods_gitlab_issues"))
            .findFirst()
            .orElseThrow()
            .getLabel())
        .isEqualTo("镜像表/ issues");
  }

  @Test
  void shouldReturnRowsForSystemTable() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 1, "status", "FAILED")));

    DatabaseTableRowsResponse response =
        service.getTableRows("gitlab_sync_logs", 1, 20, "FAILED", "id", "desc");

    assertThat(response.getTableName()).isEqualTo("gitlab_sync_logs");
    assertThat(response.getRows()).hasSize(1);
    assertThat(response.getSortField()).isEqualTo("id");
    assertThat(response.getKeyword()).isEqualTo("FAILED");
    assertThat(response.getSyncStatus()).isEqualTo("IDLE");
  }

  @Test
  void shouldReturnStatusMessageForSyncingMirrorTable() {
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setInitialized(true);
    registry.setMirrorTableName("ods_gitlab_issues");
    registry.setSourceTableName("issues");
    registry.setSyncStatus("SYNCING");
    registry.setLastSyncTime(LocalDateTime.of(2026, 4, 9, 11, 0));
    when(registryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(registry);
    when(mirrorTableDefinitionFactory.buildMirrorTableDefinition(registry))
        .thenReturn(
            new DatabaseBrowserTableDefinition(
                "镜像表/ issues",
                List.of("id", "title"),
                List.of(
                    new DatabaseTableColumn("id", "ID", true),
                    new DatabaseTableColumn("title", "Title", true)),
                "id"));
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 1, "title", "Issue")));

    DatabaseTableRowsResponse response =
        service.getTableRows("ods_gitlab_issues", 1, 20, null, null, null);

    assertThat(response.getLabel()).isEqualTo("镜像表/ issues");
    assertThat(response.getSyncStatus()).isEqualTo("SYNCING");
    assertThat(response.getStatusMessage()).isEqualTo("数据正在同步中，当前展示为历史稳定版本。");
  }

  @Test
  void shouldRefreshMirrorTableBySourceTableAndConfig() {
    GitlabMirrorTableRegistry registry = new GitlabMirrorTableRegistry();
    registry.setConfigId(60L);
    registry.setInitialized(true);
    registry.setMirrorTableName("ods_gitlab_issues_cc");
    registry.setSourceTableName("issues");
    when(registryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(registry);
    when(mirrorTableDefinitionFactory.buildMirrorTableDefinition(registry))
        .thenReturn(new DatabaseBrowserTableDefinition("issues", List.of("id"), List.of(), "id"));
    when(gitlabMirrorSyncService.refreshTablesOnDemand(60L, List.of("issues"), "database-browser:ods_gitlab_issues_cc"))
        .thenReturn(1);

    int plannedTasks = service.refreshTable("ods_gitlab_issues_cc");

    assertThat(plannedTasks).isEqualTo(1);
    verify(gitlabMirrorSyncService)
        .refreshTablesOnDemand(60L, List.of("issues"), "database-browser:ods_gitlab_issues_cc");
  }
}
