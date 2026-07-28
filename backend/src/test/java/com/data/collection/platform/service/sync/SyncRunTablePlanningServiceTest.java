package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
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
import com.data.collection.platform.service.GitlabWhitelistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SyncRunTablePlanningServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunTableStateMapper stateMapper;
  private SyncRunTableTaskMapper taskMapper;
  private JsonUtils jsonUtils;
  private GitlabConfigService configService;
  private GitlabWhitelistService whitelistService;
  private SyncRunTablePlanningService planningService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    stateMapper = mock(SyncRunTableStateMapper.class);
    taskMapper = mock(SyncRunTableTaskMapper.class);
    jsonUtils = new JsonUtils(new ObjectMapper());
    configService = mock(GitlabConfigService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    GitlabMirrorProperties mirrorProperties = new GitlabMirrorProperties();
    mirrorProperties.setLargeTableShardSyncEnabled(false);
    planningService =
        new SyncRunTablePlanningService(
            syncRunMapper,
            stateMapper,
            taskMapper,
            jsonUtils,
            configService,
            whitelistService,
            mirrorProperties);
  }

  @Test
  void shouldPlanFullSyncFromWhitelistWhenPayloadHasNoTables() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
                new TableWhitelistOption("namespaces", "Namespaces", "id", "", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId("issues".equals(state.getSourceTable()) ? 91L : 92L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(2);

    ArgumentCaptor<SyncRunTableState> stateCaptor = ArgumentCaptor.forClass(SyncRunTableState.class);
    verify(stateMapper, times(2)).insert(stateCaptor.capture());
    assertThat(stateCaptor.getAllValues())
        .extracting(SyncRunTableState::getSourceTable)
        .containsExactly("issues", "namespaces");
    assertThat(stateCaptor.getAllValues())
        .extracting(SyncRunTableState::getRowStrategy)
        .containsExactly("INCREMENTAL", "FULL_ONLY");

    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getSourceTable)
        .containsExactly("issues", "namespaces");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsExactly("FULL", "FULL");
    assertThat(taskCaptor.getAllValues())
        .allSatisfy(
            task -> {
              assertThat(task.getRunId()).isEqualTo(77L);
              assertThat(task.getConfigId()).isEqualTo(1L);
              assertThat(task.getSourceInstance()).isEqualTo("alpha");
              assertThat(task.getTaskType()).isEqualTo("FULL_SYNC");
              assertThat(task.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
              assertThat(task.getWatermarkAt()).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0));
            });
  }

  @Test
  void shouldNotDuplicateExistingTasksWhenRunIsPlannedAgain() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(taskMapper.selectList(any()))
        .thenReturn(List.of(existingTask("issues", null, null)));
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
                new TableWhitelistOption("namespaces", "Namespaces", "id", "updated_at", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId("issues".equals(state.getSourceTable()) ? 91L : 92L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(2);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getSourceTable()).isEqualTo("namespaces");
  }

  @Test
  void shouldPlanFullCompensationFromWhitelistWithReconcileStrategy() {
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
                new TableWhitelistOption("namespaces", "Namespaces", "id", "", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId(91L);
              state.setLastWatermarkAt(LocalDateTime.of(2026, 5, 20, 10, 0));
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(2);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getTaskType)
        .containsExactly("FULL_COMPENSATION_SCAN", "FULL_COMPENSATION_SCAN");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getSourceTable)
        .containsExactly("issues", "namespaces");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsExactly("FULL_RECONCILE", "FULL_RECONCILE");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getWatermarkAt)
        .containsOnly(LocalDateTime.of(1970, 1, 1, 0, 0));
  }

  @Test
  void shouldPlanCompensationFromExistingStatesWithoutWhitelistDiscovery() {
    SyncRun run = run(SyncRunType.COMPENSATION_SCAN);
    SyncRunTableState issueState = existingState(91L, "issues", "id", "updated_at");
    issueState.setLastWatermarkAt(LocalDateTime.of(2026, 5, 20, 10, 0));
    SyncRunTableState noteState = existingState(92L, "notes", "id", "updated_at");
    noteState.setLastWatermarkAt(LocalDateTime.of(2026, 5, 20, 11, 0));
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(stateMapper.selectList(any())).thenReturn(List.of(issueState, noteState));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(2);
    verify(whitelistService, never()).resolveOptions(any());
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getSourceTable)
        .containsExactly("issues", "notes");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsExactly("INCREMENTAL", "INCREMENTAL");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getWatermarkAt)
        .containsExactly(
            LocalDateTime.of(2026, 5, 20, 10, 0),
            LocalDateTime.of(2026, 5, 20, 11, 0));
  }

  @Test
  void shouldFallBackToWhitelistDiscoveryWhenCompensationHasNoExistingStates() {
    SyncRun run = run(SyncRunType.COMPENSATION_SCAN);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(stateMapper.selectList(any())).thenReturn(List.of());
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId(91L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(1);
    verify(whitelistService).resolveOptions(config);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getSourceTable()).isEqualTo("issues");
    assertThat(taskCaptor.getValue().getRowStrategy()).isEqualTo("INCREMENTAL");
  }

  @Test
  void shouldPlanSystemHookPreciseTargetsFromPayload() {
    SyncRun run = run(SyncRunType.SYSTEM_HOOK);
    GitlabSyncConfig config = config();
    Map<String, Object> payload =
        Map.of(
            "preciseTargets",
            List.of(
                Map.of("tableName", "issues", "lookupColumn", "id", "lookupValue", 101),
                Map.of("tableName", "issue_assignees", "lookupColumn", "issue_id", "lookupValue", "101")));
    run.setPayloadJson(jsonUtils.toJson(payload));
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
                new TableWhitelistOption("issue_assignees", "Issue assignees", "issue_id,user_id", "", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId("issues".equals(state.getSourceTable()) ? 91L : 92L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(2);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getRowStrategy)
        .containsExactly("PRECISE", "AUTHORITATIVE");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getLookupColumn)
        .containsExactly("id", "issue_id");
    assertThat(taskCaptor.getAllValues())
        .extracting(SyncRunTableTask::getLookupValue)
        .containsExactly("101", "101");
  }

  @Test
  void shouldIgnoreMalformedPreciseTargetsWithoutStringifyingNulls() {
    SyncRun run = run(SyncRunType.SYSTEM_HOOK);
    GitlabSyncConfig config = config();
    run.setPayloadJson(
        jsonUtils.toJson(
            Map.of(
                "preciseTargets",
                List.of(
                    Map.of("tableName", "issues", "lookupColumn", "id"),
                    Map.of("tableName", "issues", "lookupValue", "101"),
                    Map.of("lookupColumn", "id", "lookupValue", "101"),
                    Map.of("tableName", "issues", "lookupColumn", "id", "lookupValue", "101")))));
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId(91L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned = planningService.planRunTables(77L);

    assertThat(planned).isEqualTo(1);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper).insert(taskCaptor.capture());
    assertThat(taskCaptor.getValue().getLookupColumn()).isEqualTo("id");
    assertThat(taskCaptor.getValue().getLookupValue()).isEqualTo("101");
  }

  @Test
  void shouldPlanAuthoritativeIssueAssigneeScopesFromIncrementalIssueRows() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    GitlabSyncConfig config = config();
    SyncRunTableTask parentTask = new SyncRunTableTask();
    parentTask.setRunId(77L);
    parentTask.setConfigId(1L);
    parentTask.setSourceTable("issues");
    parentTask.setRowStrategy("INCREMENTAL");
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption(
                    "issue_assignees", "Issue assignees", "issue_id,user_id", "", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId(92L);
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    int planned =
        planningService.planAuthoritativeRelatedTasks(
            parentTask,
            List.of(Map.of("id", 101L), Map.of("id", 102L), Map.of("id", 101L)));

    assertThat(planned).isEqualTo(2);
    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(2)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(
            SyncRunTableTask::getSourceTable,
            SyncRunTableTask::getRowStrategy,
            SyncRunTableTask::getLookupColumn,
            SyncRunTableTask::getLookupValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "issue_assignees", "AUTHORITATIVE", "issue_id", "101"),
            org.assertj.core.groups.Tuple.tuple(
                "issue_assignees", "AUTHORITATIVE", "issue_id", "102"));
  }

  @Test
  void shouldPlanAllAuthoritativeRelationsFromIncrementalParentRows() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    GitlabSyncConfig config = config();
    SyncRunTableTask issueTask = parentTask("issues");
    SyncRunTableTask mergeRequestTask = parentTask("merge_requests");
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(configService.isSourceConfigured(config)).thenReturn(true);
    when(whitelistService.resolveOptions(config))
        .thenReturn(
            List.of(
                new TableWhitelistOption("issue_assignees", "Issue assignees", "issue_id,user_id", "", true),
                new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", "", true),
                new TableWhitelistOption("merge_request_assignees", "MR assignees", "merge_request_id,user_id", "", true),
                new TableWhitelistOption("merge_request_reviewers", "MR reviewers", "merge_request_id,user_id", "", true)));
    doAnswer(
            invocation -> {
              SyncRunTableState state = invocation.getArgument(0);
              state.setId(90L + state.getSourceTable().hashCode());
              return 1;
            })
        .when(stateMapper)
        .insert(any(SyncRunTableState.class));

    assertThat(planningService.planAuthoritativeRelatedTasks(issueTask, List.of(Map.of("id", 101L))))
        .isEqualTo(2);
    assertThat(planningService.planAuthoritativeRelatedTasks(mergeRequestTask, List.of(Map.of("id", 202L))))
        .isEqualTo(3);

    ArgumentCaptor<SyncRunTableTask> taskCaptor = ArgumentCaptor.forClass(SyncRunTableTask.class);
    verify(taskMapper, times(5)).insert(taskCaptor.capture());
    assertThat(taskCaptor.getAllValues())
        .extracting(
            SyncRunTableTask::getSourceTable,
            SyncRunTableTask::getRowStrategy,
            SyncRunTableTask::getLookupColumn,
            SyncRunTableTask::getLookupValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("issue_assignees", "AUTHORITATIVE", "issue_id", "101"),
            org.assertj.core.groups.Tuple.tuple("label_links", "AUTHORITATIVE", "target_id", "101"),
            org.assertj.core.groups.Tuple.tuple("merge_request_assignees", "AUTHORITATIVE", "merge_request_id", "202"),
            org.assertj.core.groups.Tuple.tuple("merge_request_reviewers", "AUTHORITATIVE", "merge_request_id", "202"),
            org.assertj.core.groups.Tuple.tuple("label_links", "AUTHORITATIVE", "target_id", "202"));
  }

  @Test
  void shouldNotPlanAuthoritativeRelationsForUnrelatedTable() {
    SyncRunTableTask parentTask = new SyncRunTableTask();
    parentTask.setRunId(77L);
    parentTask.setConfigId(1L);
    parentTask.setSourceTable("projects");
    parentTask.setRowStrategy("INCREMENTAL");

    int planned =
        planningService.planAuthoritativeRelatedTasks(
            parentTask, List.of(Map.of("id", 101L)));

    assertThat(planned).isZero();
    verify(syncRunMapper, never()).selectById(any());
    verify(taskMapper, never()).insert(any(SyncRunTableTask.class));
  }

  private SyncRunTableTask parentTask(String sourceTable) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(77L);
    task.setConfigId(1L);
    task.setSourceTable(sourceTable);
    task.setRowStrategy("INCREMENTAL");
    return task;
  }

  @Test
  void shouldFailFastBeforeWhitelistDiscoveryWhenSourceIsIncomplete() {
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

  private SyncRunTableTask existingTask(String sourceTable, String lookupColumn, String lookupValue) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setRunId(77L);
    task.setSourceTable(sourceTable);
    task.setLookupColumn(lookupColumn);
    task.setLookupValue(lookupValue);
    return task;
  }

  private SyncRunTableState existingState(Long id, String sourceTable, String primaryKeys, String updatedAtColumn) {
    SyncRunTableState state = new SyncRunTableState();
    state.setId(id);
    state.setConfigId(1L);
    state.setSourceInstance("alpha");
    state.setSourceTable(sourceTable);
    state.setMirrorTable("gitlab_" + sourceTable + "_alpha");
    state.setPrimaryKeyColumns(primaryKeys);
    state.setUpdatedAtColumn(updatedAtColumn);
    state.setRowStrategy("INCREMENTAL");
    state.setSyncEnabled(true);
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
