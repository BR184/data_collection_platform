package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabExternalDbService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.GitlabMirrorTableStorageService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunTableWorkerServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableTaskMapper taskMapper;
  private SyncRunTableStateMapper stateMapper;
  private GitlabConfigService configService;
  private GitlabExternalDbService externalDbService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabMirrorTableStorageService storageService;
  private SyncRunTableWorkerService workerService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    taskMapper = org.mockito.Mockito.mock(SyncRunTableTaskMapper.class);
    stateMapper = org.mockito.Mockito.mock(SyncRunTableStateMapper.class);
    configService = org.mockito.Mockito.mock(GitlabConfigService.class);
    externalDbService = org.mockito.Mockito.mock(GitlabExternalDbService.class);
    mirrorSchemaService = org.mockito.Mockito.mock(GitlabMirrorSchemaService.class);
    storageService = org.mockito.Mockito.mock(GitlabMirrorTableStorageService.class);
    workerService =
        new SyncRunTableWorkerService(
            taskMapper,
            stateMapper,
            jdbcTemplate,
            configService,
            externalDbService,
            mirrorSchemaService,
            storageService);
  }

  @Test
  void shouldScanSourceRowsAndWriteMirrorBatchForClaimedTask() {
    LocalDateTime watermark = LocalDateTime.of(2026, 5, 17, 10, 0);
    LocalDateTime nextWatermark = LocalDateTime.of(2026, 5, 17, 10, 3);
    SyncRunTableTask task = task(watermark);
    SyncRunTableState state = state(watermark);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2),
                new SourceTableColumn("title", "text", true, 3)));
    List<Map<String, Object>> rows =
        List.of(
            Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1), "title", "first"),
            Map.of("id", 102L, "updated_at", nextWatermark, "title", "second"));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), eq("table-worker"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(externalDbService.incrementalCursorScan(
            eq(config),
            argThat(option ->
                "issues".equals(option.tableName())
                    && "id".equals(option.primaryKey())
                    && "updated_at".equals(option.updatedAtColumn())),
            eq(watermark),
            isNull(),
            isNull(),
            eq(500)))
        .thenReturn(rows);
    when(storageService.upsertBatch(mirrorSchema, rows, 501L)).thenReturn(new MirrorBatchWriteResult(2, 2, 0));

    int processed = workerService.drainRunTasks(77L);

    assertThat(processed).isEqualTo(1);
    verify(mirrorSchemaService).markTableSyncing(1L, "issues");
    verify(storageService).upsertBatch(mirrorSchema, rows, 501L);
    verify(jdbcTemplate).update(contains("set status = ?"), eq("SUCCESS"), eq(2L), eq(2L), isNull(), eq(501L));
    verify(stateMapper)
        .updateById(
            argThat(
                (SyncRunTableState updated) ->
                    updated.getId().equals(91L)
                        && Boolean.FALSE.equals(updated.getDirtyFlag())
                        && nextWatermark.equals(updated.getLastWatermarkAt())
                        && "102".equals(updated.getLastCursorPk())
                        && updated.getLastSuccessAt() != null));
    verify(mirrorSchemaService).markTableIdle(eq(1L), eq("issues"), any(LocalDateTime.class));
  }

  @Test
  void shouldStopBeforeClaimingNextTableWhenRunIsCancelling() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(44L)))
        .thenReturn(true);

    int processed = workerService.drainRunTasks(44L);

    assertThat(processed).isZero();
    verify(jdbcTemplate, never())
        .queryForObject(contains("update sync_run_table_tasks"), any(RowMapper.class), any(), any(), any());
    verify(jdbcTemplate).update(contains("set status = 'CANCELLED'"), eq(44L));
  }

  @Test
  void shouldTreatMissingRunAsNotCancelled() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(99L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThat(workerService.isRunCancellationRequested(99L)).isFalse();
  }

  private SyncRunTableTask task(LocalDateTime watermark) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(77L);
    task.setConfigId(1L);
    task.setStateId(91L);
    task.setSourceInstance("alpha");
    task.setSourceTable("issues");
    task.setMirrorTable("ods_gitlab_alpha_issues");
    task.setTaskType("TABLE_REFRESH");
    task.setStatus(SyncRunStatus.RUNNING);
    task.setRowStrategy("INCREMENTAL");
    task.setWatermarkAt(watermark);
    task.setBatchSize(500);
    task.setRetryCount(0);
    task.setMaxRetryCount(3);
    return task;
  }

  private SyncRunTableState state(LocalDateTime watermark) {
    SyncRunTableState state = new SyncRunTableState();
    state.setId(91L);
    state.setConfigId(1L);
    state.setSourceInstance("alpha");
    state.setSourceTable("issues");
    state.setMirrorTable("ods_gitlab_alpha_issues");
    state.setPrimaryKeyColumns("id");
    state.setUpdatedAtColumn("updated_at");
    state.setRowStrategy("INCREMENTAL");
    state.setSyncEnabled(true);
    state.setDirtyFlag(false);
    state.setLastWatermarkAt(watermark);
    return state;
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    return config;
  }
}
