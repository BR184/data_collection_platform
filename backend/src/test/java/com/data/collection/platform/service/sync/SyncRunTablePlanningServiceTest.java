package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.GitlabWhitelistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SyncRunTablePlanningServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunTableStateMapper stateMapper;
  private SyncRunTableTaskMapper taskMapper;
  private GitlabConfigService configService;
  private GitlabWhitelistService whitelistService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private SyncRunAuthoritativeScopePlanner authoritativeScopePlanner;
  private SyncRunAuthoritativeScopeRepository authoritativeScopeRepository;
  private GitlabMirrorProperties mirrorProperties;
  private SyncRunTablePlanningService planningService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    stateMapper = mock(SyncRunTableStateMapper.class);
    taskMapper = mock(SyncRunTableTaskMapper.class);
    configService = mock(GitlabConfigService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    authoritativeScopePlanner = mock(SyncRunAuthoritativeScopePlanner.class);
    authoritativeScopeRepository = mock(SyncRunAuthoritativeScopeRepository.class);
    mirrorProperties = new GitlabMirrorProperties();
    planningService =
        new SyncRunTablePlanningService(
            syncRunMapper,
            stateMapper,
            taskMapper,
            new JsonUtils(new ObjectMapper()),
            configService,
            whitelistService,
            mirrorSchemaService,
            mirrorProperties,
            authoritativeScopePlanner,
            authoritativeScopeRepository);
  }

  @Test
  void test_whitelist_schema_is_prepared_before_any_table_task_is_enqueued() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    GitlabSyncConfig config = config();
    List<TableWhitelistOption> options =
        List.of(
            option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
            option("projects", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
            option("users", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
            option(
                "resource_label_events",
                "id",
                "",
                SourceCursorStrategy.PRIMARY_KEY_KEYSET));
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config)).thenReturn(options);
    assignStateIds();

    assertThat(planningService.planRunTables(77L)).isEqualTo(4);

    InOrder order = org.mockito.Mockito.inOrder(mirrorSchemaService, taskMapper);
    order.verify(mirrorSchemaService).prepareMirrorTablesForRun(config, options);
    order.verify(taskMapper, times(4)).insert(any(SyncRunTableTask.class));
  }

  @Test
  void test_full_sync_plans_whitelist_tables_with_full_reconcile_strategy() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("namespaces", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET)));
    assignStateIds();

    assertThat(planningService.planRunTables(77L)).isEqualTo(2);

    ArgumentCaptor<SyncRunTableTask> tasks = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(tasks.capture());
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getSourceTable)
        .containsExactly("issues", "namespaces");
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsOnly("FULL_RECONCILE");
    assertThat(tasks.getAllValues())
        .allSatisfy(
            task -> {
              assertThat(task.getTaskType()).isEqualTo("FULL_SYNC");
              assertThat(task.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
              assertThat(task.getWatermarkAt())
                  .isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
            });
  }

  @Test
  void test_replanning_same_run_does_not_duplicate_existing_table_task() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(taskMapper.selectList(any())).thenReturn(List.of(existingTask("issues")));
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("namespaces", "id", "updated_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET)));
    assignStateIds();

    assertThat(planningService.planRunTables(77L)).isEqualTo(2);

    ArgumentCaptor<SyncRunTableTask> task = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper).insert(task.capture());
    assertThat(task.getValue().getSourceTable()).isEqualTo("namespaces");
  }

  @Test
  void test_full_compensation_plans_whitelist_tables_from_initial_watermark() {
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("namespaces", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET)));
    assignStateIds();

    assertThat(planningService.planRunTables(77L)).isEqualTo(2);

    ArgumentCaptor<SyncRunTableTask> tasks = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(tasks.capture());
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getTaskType)
        .containsOnly("FULL_COMPENSATION_SCAN");
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsOnly("FULL_RECONCILE");
  }

  @Test
  void test_incremental_plans_only_fast_tables_without_delete_only_tasks() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("issue_assignees", "issue_id,user_id", "", SourceCursorStrategy.NONE),
                option("label_links", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("resource_label_events", "id", "", SourceCursorStrategy.NONE)));
    assignStateIds();

    assertThat(planningService.planRunTables(77L)).isEqualTo(3);

    ArgumentCaptor<SyncRunTableTask> tasks = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(3)).insert(tasks.capture());
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getSourceTable)
        .containsExactly("issues", "label_links", "resource_label_events");
    assertThat(tasks.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsExactly("INCREMENTAL", "INCREMENTAL", "MONOTONIC_PRIMARY_KEY")
        .doesNotContain("DELETE_ONLY");
    verify(authoritativeScopeRepository)
        .snapshotSelectedSourceTables(
            77L,
            List.of(
                "issues", "issue_assignees", "label_links", "resource_label_events"));
  }

  @Test
  void test_delete_reconciliation_plans_only_due_tables() {
    SyncRun run = run(SyncRunType.DELETE_RECONCILIATION);
    GitlabSyncConfig config = config();
    mirrorProperties.setDeleteReconciliationIntervalMinutes(60);
    mirrorProperties.setDeleteReconciliationPageSize(321);
    SyncRunTableState due = existingState(91L, "issues", null);
    SyncRunTableState notDue =
        existingState(92L, "label_links", LocalDateTime.now().plusDays(1));
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option(
                    "label_links",
                    "id",
                    "updated_at",
                    SourceCursorStrategy.TIMESTAMP_KEYSET)));
    when(stateMapper.selectOne(any())).thenReturn(due, notDue);

    assertThat(planningService.planRunTables(77L)).isEqualTo(1);

    ArgumentCaptor<SyncRunTableTask> task = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper).insert(task.capture());
    assertThat(task.getValue().getSourceTable()).isEqualTo("issues");
    assertThat(task.getValue().getTaskType()).isEqualTo("DELETE_RECONCILIATION");
    assertThat(task.getValue().getRowStrategy()).isEqualTo("RECONCILE_ONLY");
    assertThat(task.getValue().getTaskStage())
        .isEqualTo(com.data.collection.platform.entity.sync.SyncRunTableTaskStage.RECONCILE);
    assertThat(task.getValue().getBatchSize()).isEqualTo(321);
  }

  @Test
  void test_system_hook_authoritative_targets_enter_scope_queue_not_table_tasks() {
    SyncRun run = run(SyncRunType.SYSTEM_HOOK);
    GitlabSyncConfig config = config();
    run.setPayloadJson(
        """
        {"preciseTargets":[
          {"tableName":"issues","lookupScope":{"id":"101"}},
          {"tableName":"issue_assignees","lookupScope":{"issue_id":"101"}},
          {"tableName":"label_links","lookupScope":{"target_id":"101","target_type":"Issue"}}
        ]}
        """);
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET),
                option("issue_assignees", "issue_id,user_id", "", SourceCursorStrategy.NONE),
                option("label_links", "id", "", SourceCursorStrategy.NONE)));
    when(authoritativeScopePlanner.enqueueDeclaredScope(
            eq(77L), eq("alpha"), any(), any(), any()))
        .thenReturn(1);

    assertThat(planningService.planRunTables(77L)).isEqualTo(3);

    verify(taskMapper, never()).insert(any(SyncRunTableTask.class));
    verify(authoritativeScopePlanner, times(3))
        .enqueueDeclaredScope(eq(77L), eq("alpha"), any(), any(), any());
  }

  @Test
  void test_malformed_system_hook_targets_are_ignored_without_stringifying_nulls() {
    SyncRun run = run(SyncRunType.SYSTEM_HOOK);
    GitlabSyncConfig config = config();
    run.setPayloadJson(
        """
        {"preciseTargets":[
          {"tableName":"issues","lookupScope":{}},
          {"tableName":"issues"},
          {"lookupScope":{"id":"101"}},
          {"tableName":"issues","lookupScope":{"id":"101"}}
        ]}
        """);
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(option("issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET)));
    when(authoritativeScopePlanner.enqueueDeclaredScope(
            eq(77L), eq("alpha"), eq("issues"), any(), any()))
        .thenReturn(1);

    assertThat(planningService.planRunTables(77L)).isEqualTo(1);

    verify(authoritativeScopePlanner)
        .enqueueDeclaredScope(
            77L,
            "alpha",
            "issues",
            "system-hook:issues",
            Map.of("id", "101"));
  }

  @Test
  void test_incomplete_source_fails_before_whitelist_discovery() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(false);

    assertThatThrownBy(() -> planningService.planRunTables(77L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("连接配置不完整");

    verify(whitelistService, never()).resolveOptions(any());
    verify(taskMapper, never()).insert(any(SyncRunTableTask.class));
  }

  private void assignStateIds() {
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId((long) Math.abs(state.getSourceTable().hashCode()));
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));
  }

  private SyncRun run(SyncRunType runType) {
    SyncRun run = new SyncRun();
    run.setId(77L);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(runType);
    run.setStatus(SyncRunStatus.QUEUED);
    run.setPayloadJson("{}");
    return run;
  }

  private SyncRunTableTask existingTask(String sourceTable) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(77L);
    task.setSourceTable(sourceTable);
    return task;
  }

  private SyncRunTableState existingState(
      Long id, String sourceTable, LocalDateTime lastDeleteReconciledAt) {
    SyncRunTableState state = new SyncRunTableState();
    state.setId(id);
    state.setConfigId(1L);
    state.setSourceInstance("alpha");
    state.setSourceTable(sourceTable);
    state.setLastDeleteReconciledAt(lastDeleteReconciledAt);
    return state;
  }

  private TableWhitelistOption option(
      String tableName,
      String primaryKey,
      String updatedAtColumn,
      SourceCursorStrategy cursorStrategy) {
    return new TableWhitelistOption(
        tableName, tableName, primaryKey, updatedAtColumn, cursorStrategy, true);
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
