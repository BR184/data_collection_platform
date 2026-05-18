package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
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
    planningService =
        new SyncRunTablePlanningService(
            syncRunMapper,
            stateMapper,
            taskMapper,
            jsonUtils,
            configService,
            whitelistService);
  }

  @Test
  void shouldPlanFullSyncFromWhitelistWhenPayloadHasNoTables() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectById(77L)).thenReturn(run);
    when(configService.getConfigById(1L)).thenReturn(config);
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
        .containsExactly("PRECISE", "PRECISE");
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

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    return config;
  }
}
