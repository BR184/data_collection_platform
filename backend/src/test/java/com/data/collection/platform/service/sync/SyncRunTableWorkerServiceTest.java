package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunTableWorkerServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunTableTaskMapper taskMapper;
  private SyncRunTableStateMapper stateMapper;
  private GitlabConfigService configService;
  private SyncRunTableTaskLeaseService taskLeaseService;
  private SourceTableReader sourceTableReader;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private MirrorTableWriter mirrorTableWriter;
  private SyncTableContinuationPlanner continuationPlanner;
  private SyncRunTablePlanningService tablePlanningService;
  private SyncRunTableTaskHeartbeatService heartbeatService;
  private SyncRunTablePageCommitService pageCommitService;
  private SyncRunYieldService yieldService;
  private SyncRunTableTaskExecutor taskExecutor;
  private SyncRunTableWorkerService workerService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    taskMapper = org.mockito.Mockito.mock(SyncRunTableTaskMapper.class);
    stateMapper = org.mockito.Mockito.mock(SyncRunTableStateMapper.class);
    configService = org.mockito.Mockito.mock(GitlabConfigService.class);
    taskLeaseService = new SyncRunTableTaskLeaseService(jdbcTemplate);
    sourceTableReader = org.mockito.Mockito.mock(SourceTableReader.class);
    mirrorSchemaService = org.mockito.Mockito.mock(GitlabMirrorSchemaService.class);
    mirrorTableWriter = org.mockito.Mockito.mock(MirrorTableWriter.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(0);
    properties.setTableTaskLeaseSeconds(30);
    continuationPlanner = new SyncTableContinuationPlanner(taskMapper, properties);
    tablePlanningService = org.mockito.Mockito.mock(SyncRunTablePlanningService.class);
    heartbeatService = new SyncRunTableTaskHeartbeatService(taskLeaseService, properties);
    yieldService = org.mockito.Mockito.mock(SyncRunYieldService.class);
    pageCommitService =
        new SyncRunTablePageCommitService(
            taskLeaseService,
            stateMapper,
            continuationPlanner,
            tablePlanningService,
            mirrorTableWriter);
    when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    taskExecutor =
        new SyncRunTableTaskExecutor(
            stateMapper,
            new JsonUtils(new ObjectMapper()),
            taskLeaseService,
            heartbeatService,
            pageCommitService,
            configService,
            sourceTableReader,
            mirrorSchemaService,
            mirrorTableWriter);
    workerService =
        new SyncRunTableWorkerService(
            jdbcTemplate,
            taskLeaseService,
            heartbeatService,
            yieldService,
            taskExecutor);
  }

  @AfterEach
  void tearDown() {
    heartbeatService.shutdown();
  }

  @Test
  void shouldScanSourceRowsAndWriteMirrorBatchForClaimedTask() {
    LocalDateTime watermark = LocalDateTime.of(2026, 5, 17, 10, 0);
    LocalDateTime nextWatermark = LocalDateTime.of(2026, 5, 17, 10, 3);
    SyncRunTableTask task = task(watermark);
    task.setScanUpperBoundAt(nextWatermark);
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
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readIncrementalBatch(
            eq(config),
            argThat(option ->
                "issues".equals(option.tableName())
                    && "id".equals(option.primaryKey())
                    && "updated_at".equals(option.updatedAtColumn())),
            eq(mirrorSchema),
            eq(watermark),
            eq(nextWatermark),
            isNull(),
            isNull(),
            eq(500)))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L)).thenReturn(new MirrorBatchWriteResult(2, 2, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(mirrorSchemaService).markTableSyncing(1L, "issues");
    verify(mirrorTableWriter).writeBatch(mirrorSchema, rows, 501L);
    verify(stateMapper)
        .updateById(
            argThat(
                (SyncRunTableState updated) ->
                    updated.getId().equals(91L)
                        && Boolean.FALSE.equals(updated.getDirtyFlag())
                        && nextWatermark.equals(updated.getLastWatermarkAt())
                        && "".equals(updated.getLastCursorPk())
                        && updated.getLastSuccessAt() != null));
    verify(mirrorSchemaService).markTableIdle(eq(1L), eq("issues"), any(LocalDateTime.class));
  }

  @Test
  void shouldCancelClaimedTaskAfterSourceScanBeforeMirrorWrite() {
    LocalDateTime watermark = LocalDateTime.of(2026, 5, 17, 10, 0);
    SyncRunTableTask task = task(watermark);
    task.setScanUpperBoundAt(watermark.plusMinutes(3));
    SyncRunTableState state = state(watermark);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    List<Map<String, Object>> rows =
        List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1)));
    AtomicBoolean sourceScanCompleted = new AtomicBoolean(false);

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenAnswer(invocation -> sourceScanCompleted.get());
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task);
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readIncrementalBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            eq(watermark),
            eq(watermark.plusMinutes(3)),
            isNull(),
            isNull(),
            eq(500)))
        .thenAnswer(
            invocation -> {
              sourceScanCompleted.set(true);
              return rows;
            });

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(mirrorTableWriter, never()).writeBatch(any(), any(), any());
  }

  @Test
  void shouldUsePrimaryKeyCursorScanForFullSyncTask() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("FULL_SYNC");
    task.setRowStrategy("FULL_RECONCILE");
    SyncRunTableState state = state(null);
    state.setUpdatedAtColumn("");
    state.setRowStrategy("FULL_ONLY");
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    List<Map<String, Object>> rows = List.of(Map.of("id", 101L), Map.of("id", 102L));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            isNull(),
            eq(500)))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L, true))
        .thenReturn(new MirrorBatchWriteResult(2, 2, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(sourceTableReader)
        .readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            isNull(),
            eq(500));
    verify(sourceTableReader, never())
        .readIncrementalBatch(any(), any(), any(), any(), any(), any(), any(), anyInt());
    verify(stateMapper)
        .updateById(
            argThat(
                (SyncRunTableState updated) ->
                    updated.getId().equals(91L)
                        && Boolean.TRUE.equals(updated.getDirtyFlag())
                        && updated.getLastWatermarkAt() == null
                        && updated.getLastSuccessAt() != null));
  }

  @Test
  void shouldQueueNextFullSyncBatchWhenPrimaryKeyCursorBatchIsFull() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("FULL_SYNC");
    task.setRowStrategy("FULL_RECONCILE");
    task.setBatchSize(2);
    task.setScanUpperBoundAt(LocalDateTime.of(2026, 5, 17, 10, 2));
    SyncRunTableState state = state(null);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(
            Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1)),
            Map.of("id", 102L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 2)));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            isNull(),
            eq(2)))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L, true))
        .thenReturn(new MirrorBatchWriteResult(2, 2, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(taskMapper)
        .insert(
            argThat(
                (SyncRunTableTask nextTask) ->
                    nextTask.getRunId().equals(77L)
                        && "FULL_RECONCILE".equals(nextTask.getRowStrategy())
                        && "[\"102\"]".equals(nextTask.getCursorPk())
                        && nextTask.getBatchSize().equals(2)
                        && nextTask.getPageNumber().equals(2)
                        && nextTask.getScanUpperBoundAt().equals(task.getScanUpperBoundAt())));
    verify(sourceTableReader, never()).findMaxUpdatedAt(any(), any());
    verify(stateMapper)
        .updateById(
            argThat(
                (SyncRunTableState updated) ->
                    updated.getId().equals(91L)
                        && Boolean.TRUE.equals(updated.getDirtyFlag())
                        && updated.getLastWatermarkAt() == null));
  }

  @Test
  void shouldPauseFullSyncAfterCommittedPageWithoutClaimingContinuation() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("FULL_SYNC");
    task.setRowStrategy("FULL_RECONCILE");
    task.setBatchSize(2);
    task.setScanUpperBoundAt(LocalDateTime.of(2026, 5, 17, 10, 2));
    SyncRunTableState state = state(null);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(
            Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1)),
            Map.of("id", 102L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 2)));
    SyncRun run = run(77L);
    run.setRunType(SyncRunType.FULL_SYNC);
    run.setStatus(SyncRunStatus.RUNNING);

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"),
            any(RowMapper.class),
            startsWith("table-worker-77-"),
            eq(30),
            eq(77L)))
        .thenReturn(task);
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(
            eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(
            new GitlabMirrorSchemaService.PreparedMirrorTable(
                mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            isNull(),
            eq(2)))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L, true))
        .thenReturn(new MirrorBatchWriteResult(2, 2, 0));
    when(yieldService.shouldYield(run)).thenReturn(true);
    when(yieldService.pauseIfRequested(run))
        .thenAnswer(
            invocation -> {
              run.setStatus(SyncRunStatus.PAUSED);
              return true;
            });

    SyncRunTableWorkerService.DrainResult result = workerService.drainRunTasks(run, 1);

    assertThat(result.processedTasks()).isEqualTo(1);
    assertThat(result.yielded()).isTrue();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PAUSED);
    verify(mirrorTableWriter).writeBatch(mirrorSchema, rows, 501L, true);
    verify(taskMapper)
        .insert(
            argThat(
                (SyncRunTableTask nextTask) ->
                        nextTask.getRunId().equals(77L)
                            && nextTask.getStatus() == SyncRunStatus.QUEUED
                        && "[\"102\"]".equals(nextTask.getCursorPk())));
    verify(jdbcTemplate, times(1))
        .queryForObject(
            contains("update sync_run_table_tasks"),
            any(RowMapper.class),
            startsWith("table-worker-77-"),
            eq(30),
            eq(77L));
    verify(yieldService).pauseIfRequested(run);
  }

  @Test
  void shouldQueueReconciliationAfterFinalFullSyncBatch() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("FULL_SYNC");
    task.setRowStrategy("FULL_RECONCILE");
    task.setCursorPk("[\"100\"]");
    task.setBatchSize(500);
    SyncRunTableState state = state(null);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1)));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            eq("[\"100\"]"),
            eq(500)))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L, true))
        .thenReturn(new MirrorBatchWriteResult(1, 1, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(taskMapper)
        .insert(
            argThat(
                (SyncRunTableTask reconciliationTask) ->
                    reconciliationTask.getTaskStage() == SyncRunTableTaskStage.RECONCILE
                        && reconciliationTask.getParentTaskId().equals(501L)
                        && "FULL_RECONCILE".equals(reconciliationTask.getRowStrategy())));
    verify(stateMapper)
        .updateById(
            argThat(
                (SyncRunTableState updated) ->
                    updated.getId().equals(91L)
                        && Boolean.TRUE.equals(updated.getDirtyFlag())
                        && updated.getLastWatermarkAt() == null));
  }

  @Test
  void shouldReconcileFullCompensationThroughResumableScanAndReconcileTasks() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("FULL_COMPENSATION_SCAN");
    task.setRowStrategy("FULL_RECONCILE");
    task.setBatchSize(500);
    SyncRunTableTask reconciliationTask = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    reconciliationTask.setId(502L);
    reconciliationTask.setTaskType("FULL_COMPENSATION_SCAN");
    reconciliationTask.setRowStrategy("FULL_RECONCILE");
    reconciliationTask.setTaskStage(SyncRunTableTaskStage.RECONCILE);
    SyncRunTableState state = state(null);
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
        List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 17, 10, 1), "title", "fixed"));
    List<Map<String, Object>> mirrorKeys = List.of(Map.of("id", "101"), Map.of("id", "999"));
    List<Map<String, Object>> mirrorOnlyKeys = List.of(Map.of("id", "999"));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenReturn(reconciliationTask)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readFullBatch(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(mirrorSchema),
            isNull(),
            eq(500)))
        .thenReturn(rows);
    when(sourceTableReader.findExistingPrimaryKeySignatures(
            eq(config),
            argThat((TableWhitelistOption option) -> "issues".equals(option.tableName())),
            eq(mirrorKeys)))
        .thenReturn(Set.of("101"));
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L, true)).thenReturn(new MirrorBatchWriteResult(1, 1, 0));
    when(mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, null, 500))
        .thenReturn(new MirrorPrimaryKeyBatch(mirrorKeys, null));
    when(mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyKeys, 502L))
        .thenReturn(1);

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(2);
    verify(mirrorTableWriter).writeBatch(mirrorSchema, rows, 501L, true);
    verify(sourceTableReader).findExistingPrimaryKeySignatures(eq(config), any(), eq(mirrorKeys));
    verify(mirrorTableWriter).markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyKeys, 502L);
    verify(taskMapper)
        .insert(
            argThat(
                (SyncRunTableTask nextTask) ->
                    nextTask.getTaskStage() == SyncRunTableTaskStage.RECONCILE
                        && nextTask.getParentTaskId().equals(501L)));
    assertThat(state.getDirtyFlag()).isFalse();
    assertThat(state.getLastFullVerifiedAt()).isNotNull();
  }

  @Test
  void shouldUsePreciseScanForSystemHookTask() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("SYSTEM_HOOK");
    task.setRowStrategy("AUTHORITATIVE");
    task.setLookupScopeJson("{\"issue_id\":\"101\"}");
    SyncRunTableState state = state(null);
    state.setSourceTable("issue_assignees");
    state.setPrimaryKeyColumns("issue_id,user_id");
    state.setUpdatedAtColumn("");
    state.setRowStrategy("FULL_ONLY");
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issue_assignees",
            List.of("issue_id", "user_id"),
            "",
            List.of(new SourceTableColumn("issue_id", "bigint", false, 1)));
    List<Map<String, Object>> rows = List.of(Map.of("issue_id", 101L, "user_id", 7L));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(eq(config), argThat(option -> "issue_assignees".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(mirrorSchema, "ods_gitlab_alpha_issue_assignees", true, null));
    when(sourceTableReader.readPrecise(
            eq(config),
            argThat(option -> "issue_assignees".equals(option.tableName())),
            eq(Map.of("issue_id", "101"))))
        .thenReturn(rows);
    when(mirrorTableWriter.replaceAuthoritativeScope(
            mirrorSchema, Map.of("issue_id", "101"), rows, 501L))
        .thenReturn(new MirrorBatchWriteResult(1, 1, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(sourceTableReader).readPrecise(
        eq(config),
        argThat(option -> "issue_assignees".equals(option.tableName())),
        eq(Map.of("issue_id", "101")));
    verify(mirrorTableWriter)
        .replaceAuthoritativeScope(mirrorSchema, Map.of("issue_id", "101"), rows, 501L);
  }

  @Test
  void shouldReplaceTypedLabelScopeWithEmptySourceCollection() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("SYSTEM_HOOK");
    task.setRowStrategy("AUTHORITATIVE");
    task.setLookupScopeJson("{\"target_id\":\"101\",\"target_type\":\"Issue\"}");
    SyncRunTableState state = state(null);
    state.setSourceTable("label_links");
    state.setPrimaryKeyColumns("label_id,target_id,target_type");
    state.setUpdatedAtColumn("");
    state.setRowStrategy("FULL_ONLY");
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_label_links",
            List.of("label_id", "target_id", "target_type"),
            "",
            List.of(
                new SourceTableColumn("label_id", "bigint", false, 1),
                new SourceTableColumn("target_id", "bigint", false, 2),
                new SourceTableColumn("target_type", "text", false, 3)));
    Map<String, Object> lookupScope = Map.of("target_id", "101", "target_type", "Issue");

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), startsWith("table-worker-77-"), eq(30), eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(
            eq(config), argThat(option -> "label_links".equals(option.tableName()))))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(
            mirrorSchema, "ods_gitlab_label_links", true, null));
    when(sourceTableReader.readPrecise(
            eq(config),
            argThat(option -> "label_links".equals(option.tableName())),
            eq(lookupScope)))
        .thenReturn(List.of());
    when(mirrorTableWriter.replaceAuthoritativeScope(
            mirrorSchema, lookupScope, List.of(), 501L))
        .thenReturn(new MirrorBatchWriteResult(0, 1, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(mirrorTableWriter)
        .replaceAuthoritativeScope(mirrorSchema, lookupScope, List.of(), 501L);
  }

  @Test
  void shouldKeepUpdatedAtPreciseTableOnStandardUpsertPath() {
    SyncRunTableTask task = task(LocalDateTime.of(1970, 1, 1, 0, 0));
    task.setTaskType("SYSTEM_HOOK");
    task.setRowStrategy("PRECISE");
    task.setLookupScopeJson("{\"id\":\"101\"}");
    SyncRunTableState state = state(null);
    GitlabSyncConfig config = config();
    SourceTableSchema mirrorSchema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(
            Map.of(
                "id", 101L,
                "updated_at", LocalDateTime.of(2026, 7, 28, 10, 0)));

    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(77L)))
        .thenReturn(false, false, false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"),
            any(RowMapper.class),
            startsWith("table-worker-77-"),
            eq(30),
            eq(77L)))
        .thenReturn(task)
        .thenThrow(new EmptyResultDataAccessException(1));
    when(stateMapper.selectById(91L)).thenReturn(state);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(mirrorSchemaService.getPreparedMirrorTableForSync(
            eq(config), argThat(option -> "issues".equals(option.tableName()))))
        .thenReturn(
            new GitlabMirrorSchemaService.PreparedMirrorTable(
                mirrorSchema, "ods_gitlab_alpha_issues", true, null));
    when(sourceTableReader.readPrecise(
            eq(config),
            argThat(option -> "issues".equals(option.tableName())),
            eq(Map.of("id", "101"))))
        .thenReturn(rows);
    when(mirrorTableWriter.writeBatch(mirrorSchema, rows, 501L))
        .thenReturn(new MirrorBatchWriteResult(1, 1, 0));

    int processed = workerService.drainRunTasks(run(77L), 1).processedTasks();

    assertThat(processed).isEqualTo(1);
    verify(mirrorTableWriter).writeBatch(mirrorSchema, rows, 501L);
    verify(mirrorTableWriter, never())
        .replaceAuthoritativeScope(any(), any(), any(), any());
  }

  @Test
  void shouldStopBeforeClaimingNextTableWhenRunIsCancelling() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(44L)))
        .thenReturn(true);

    int processed = workerService.drainRunTasks(run(44L), 1).processedTasks();

    assertThat(processed).isZero();
    verify(jdbcTemplate, never())
        .queryForObject(contains("update sync_run_table_tasks"), any(RowMapper.class), any(), any(), any());
    verify(jdbcTemplate).update(contains("set status = 'CANCELLED'"), eq(44L));
  }

  @Test
  void shouldDrainWithMultipleWorkersWhenNoTableTaskIsQueued() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(88L)))
        .thenReturn(false);
    when(jdbcTemplate.queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), contains("table-worker-"), eq(30), eq(88L)))
        .thenThrow(new EmptyResultDataAccessException(1));
    when(jdbcTemplate.queryForObject(
            contains("count(*) as planned_tasks"), any(RowMapper.class), eq(88L)))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(0, 0, 0L, 0L));

    int processed = workerService.drainRunTasks(run(88L), 3).processedTasks();

    assertThat(processed).isZero();
    ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeast(3))
        .queryForObject(
            contains("update sync_run_table_tasks"), any(RowMapper.class), ownerCaptor.capture(), eq(30), eq(88L));
    assertThat(ownerCaptor.getAllValues())
        .hasSize(3)
        .allMatch(owner -> owner.startsWith("table-worker-88-"))
        .doesNotHaveDuplicates();
  }

  @Test
  void shouldTreatMissingRunAsNotCancelled() {
    when(jdbcTemplate.queryForObject(contains("select cancel_requested"), eq(Boolean.class), eq(99L)))
        .thenThrow(new EmptyResultDataAccessException(1));

    assertThat(workerService.isRunCancellationRequested(99L)).isFalse();
  }

  @Test
  void shouldRecoverTimedOutRunningTasks() {
    when(jdbcTemplate.update(contains("retry_count = retry_count + 1"))).thenReturn(2);
    when(jdbcTemplate.update(contains("set status = 'TIMEOUT'"))).thenReturn(1);

    int recovered = workerService.recoverTimedOutTasks();

    assertThat(recovered).isEqualTo(3);
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
    task.setTaskStage(SyncRunTableTaskStage.SCAN);
    task.setWatermarkAt(watermark);
    task.setPageNumber(1);
    task.setBatchSize(500);
    task.setLeaseOwner("table-worker");
    task.setRetryCount(0);
    task.setMaxRetryCount(3);
    return task;
  }

  private SyncRun run(Long id) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunType(SyncRunType.TABLE_REFRESH);
    run.setExclusiveScope("source:1:alpha:mirror");
    return run;
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
    state.setCursorStrategy(SourceCursorStrategy.PRIMARY_KEY_KEYSET);
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
