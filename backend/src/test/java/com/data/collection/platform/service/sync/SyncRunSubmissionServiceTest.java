package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.WorkspaceScopeSelectionType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunSubmissionServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunPublicationFenceService publicationFenceService;
  private SyncRunSubmissionService submissionService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    publicationFenceService =
        org.mockito.Mockito.mock(SyncRunPublicationFenceService.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    submissionService =
        new SyncRunSubmissionService(
            syncRunMapper,
            new SyncRunPolicyService(),
            jdbcTemplate,
            new JsonUtils(new ObjectMapper()),
            new SyncThreadBudgetResolver(properties),
            publicationFenceService);
  }

  @Test
  void shouldQueueFullSyncWhenNoActiveRunExists() {
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    var result = submissionService.submitFullSync(config, "Manual full sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getConfigId()).isEqualTo(12L);
    assertThat(saved.getSourceInstance()).isEqualTo("default");
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FULL_SYNC);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getPriority()).isEqualTo(40);
    assertThat(saved.getResolvedWorkerCount()).isPositive();
    assertThat(saved.getExclusiveScope()).isEqualTo("source:12:default:mirror");
    assertThat(saved.getThreadMode()).isEqualTo(SyncThreadBudgetResolver.MODE_CPU_RATIO);
    assertThat(saved.getThreadValue()).isEqualByComparingTo(new BigDecimal("0.8"));
    assertThat(saved.getRunId()).startsWith("sr_fs_default_");
    assertThat(saved.getRunId()).hasSizeLessThanOrEqualTo(64);
    assertThat(result.runId()).isEqualTo(saved.getId());
    assertThat(result.type()).isEqualTo(SyncType.FULL);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
    verify(jdbcTemplate).queryForObject(
        contains("pg_advisory_xact_lock"), eq(Object.class), eq("source:12:default:mirror"));
  }

  @Test
  void shouldQueueManualFullFactRebuildWithExplicitPayload() {
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    var result = submissionService.submitManualFullFactRebuild(config);

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FACT_REFRESH);
    assertThat(saved.getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(saved.getExclusiveScope()).isEqualTo("source:12:default:fact");
    assertThat(saved.getRequestReason()).isEqualTo("手动重建当前数据源全部事实层");
    assertThat(saved.getPayloadJson())
        .contains("\"fullBuild\":true")
        .contains("\"manualFullRebuild\":true");
    assertThat(result.type()).isEqualTo(SyncType.COMPENSATION);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    verify(jdbcTemplate).queryForObject(
        contains("pg_advisory_xact_lock"),
        eq(Object.class),
        eq("source:12:default:submission"));
  }

  @Test
  void shouldRejectManualFullFactRebuildWhenSourceHasActiveRun() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(19L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    assertThatThrownBy(() -> submissionService.submitManualFullFactRebuild(config))
        .isInstanceOf(BizException.class)
        .hasMessage("当前数据源存在同步或事实刷新任务，请等待任务完成后再重建事实层");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
  }

  @Test
  void shouldRejectSyncSubmissionWhenManualFullFactRebuildIsActive() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(20L, SyncRunType.FACT_REFRESH, SyncRunStatus.RUNNING, "source:12:default:fact");
    activeRun.setPayloadJson("{\"manualFullRebuild\":true}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    assertThatThrownBy(() -> submissionService.submitIncrementalSync(config, null, "Manual incremental sync"))
        .isInstanceOf(BizException.class)
        .hasMessage("当前数据源正在重建事实层，请等待任务完成后再提交同步或刷新任务");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
  }

  @Test
  void shouldDefaultMissingTriggerTypeToManual() {
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    submissionService.submitIncrementalSync(config, null, "Manual incremental sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.INCREMENTAL_SYNC);
    assertThat(saved.getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(saved.getPayloadJson()).contains("\"triggerType\":\"MANUAL\"");
  }

  @Test
  void shouldKeepGeneratedRunIdWithinDatabaseLimitForLongSourceInstance() {
    GitlabSyncConfig config = config();
    config.setSourceInstance("jitter_smoke");
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    submissionService.submitIncrementalSync(config, null, "Manual incremental sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunId()).hasSizeLessThanOrEqualTo(64);
    assertThat(saved.getRunId()).startsWith("sr_is_default_");
  }

  @Test
  void shouldTruncateGeneratedRunIdSourceSegmentWhenSourceInstanceIsNearLimit() {
    GitlabSyncConfig config = config();
    config.setSourceInstance("source_instance_with_a_very_long_internal_name_for_limit_check");
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    submissionService.submitIncrementalSync(config, null, "Manual incremental sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunId()).hasSizeLessThanOrEqualTo(64);
    assertThat(saved.getRunId()).startsWith("sr_is_default_");
  }

  @Test
  void shouldReuseActiveFullSyncInsteadOfCreatingAnotherOne() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(77L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitFullSync(config, "Manual full sync");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
    verify(jdbcTemplate, never()).update(
        ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    assertThat(result.runId()).isEqualTo(77L);
    assertThat(result.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_ACTIVE);
  }

  @Test
  void shouldQueueTableRefreshBehindActiveFullSyncSoBackgroundCanYield() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(91L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues", "labels"), "Need refresh");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.TABLE_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getParentRunId()).isNull();
    assertThat(saved.getRequestReason()).isEqualTo("Need refresh");
    assertThat(saved.getPlannedTableCount()).isEqualTo(2);
    assertThat(saved.getCompletedTableCount()).isZero();
    assertThat(saved.getFinishedAt()).isNull();
    assertThat(saved.getPayloadJson())
        .contains("\"sourceTables\":[\"issues\",\"labels\"]")
        .contains("\"primaryTableName\":\"issues\"");
    verify(jdbcTemplate, never())
        .update(
            ArgumentMatchers.contains("insert into sync_run_events"),
            ArgumentMatchers.<Object[]>any());
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
  }

  @Test
  void shouldQueueTableRefreshBehindActiveFullCompensationInsteadOfDeduplicating() {
    GitlabSyncConfig config = config();
    SyncRun activeRun =
        activeRun(92L, SyncRunType.FULL_COMPENSATION_SCAN, SyncRunStatus.RUNNING, "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues"), "Need refresh");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.TABLE_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getPriority()).isEqualTo(100);
    assertThat(saved.getExclusiveScope()).isEqualTo("source:12:default:mirror");
    assertThat(saved.getPayloadJson()).contains("\"sourceTables\":[\"issues\"]");
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
  }

  @Test
  void shouldDetectActiveFullCompensationRunForSameSource() {
    GitlabSyncConfig config = config();
    SyncRun activeRun =
        activeRun(93L, SyncRunType.FULL_COMPENSATION_SCAN, SyncRunStatus.RUNNING, "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    assertThat(submissionService.hasActiveFullCompensationRun(config)).isTrue();

    verify(syncRunMapper).selectList(any());
  }

  @Test
  void shouldQueueIncrementalWhenOnlyNarrowTableRefreshExists() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(101L, SyncRunType.TABLE_REFRESH, SyncRunStatus.QUEUED, "source:12:default:mirror");
    activeRun.setPayloadJson("{\"sourceTables\":[\"issues\"]}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitIncrementalSync(config, null, "Manual incremental sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.INCREMENTAL_SYNC);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getParentRunId()).isNull();
    assertThat(saved.getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(saved.getFinishedAt()).isNull();
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
  }

  @Test
  void shouldDeduplicateSameTableRefreshWhenAlreadyQueued() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(102L, SyncRunType.TABLE_REFRESH, SyncRunStatus.QUEUED, "source:12:default:mirror");
    activeRun.setPayloadJson("{\"sourceTables\":[\"issues\",\"notes\"]}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues", "notes"), "Board refresh");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
    assertThat(result.runId()).isEqualTo(102L);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_QUEUED);
  }

  @Test
  void test_reused_run_finished_before_fence_registration_queues_replacement_run() {
    GitlabSyncConfig config = config();
    SyncRun activeRun =
        activeRun(
            102L,
            SyncRunType.TABLE_REFRESH,
            SyncRunStatus.RUNNING,
            "source:12:default:mirror");
    activeRun.setPayloadJson("{\"sourceTables\":[\"issues\",\"notes\"]}");
    when(syncRunMapper.selectList(any()))
        .thenReturn(List.of(activeRun), List.of(activeRun), List.of(), List.of());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ((SyncRun) invocation.getArgument(0)).setId(103L);
              return 1;
            })
        .when(syncRunMapper)
        .insert(any(SyncRun.class));
    SyncRunPayload.WorkspaceRefreshSpec refresh =
        new SyncRunPayload.WorkspaceRefreshSpec(
            "customer-issue-board",
            List.of(FactType.ISSUE),
            WorkspaceScopeSelectionType.GLOBAL,
            List.of("*"));
    when(publicationFenceService.registerRequest(102L, "default", refresh))
        .thenReturn(false);
    when(publicationFenceService.registerRequest(103L, "default", refresh))
        .thenReturn(true);

    var result =
        submissionService.submitTableRefresh(
            config,
            List.of("Issues", "notes"),
            "Board refresh",
            Map.of("workspaceRefresh", refresh));

    assertThat(result.runId()).isEqualTo(103L);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
    verify(syncRunMapper).insert(any(SyncRun.class));
    verify(publicationFenceService).registerRequest(102L, "default", refresh);
    verify(publicationFenceService).registerRequest(103L, "default", refresh);
  }

  @Test
  void shouldMergeQueuedLowerPriorityMirrorRunsWhenFullSyncIsSubmitted() {
    GitlabSyncConfig config = config();
    SyncRun queuedCompensation =
        activeRun(
            103L,
            SyncRunType.FULL_COMPENSATION_SCAN,
            SyncRunStatus.QUEUED,
            "source:12:default:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(queuedCompensation));

    submissionService.submitFullSync(config, "Manual full sync");

    verify(jdbcTemplate).update(
        contains("status = 'MERGED'"),
        any(),
        any(),
        eq(12L),
        eq("default"),
        eq("source:12:default:mirror"),
        eq(40));
    verify(syncRunMapper).insert(any(SyncRun.class));
  }

  @Test
  void shouldReuseFactRefreshForSameMirrorParent() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(104L, SyncRunType.FACT_REFRESH, SyncRunStatus.QUEUED, "source:12:default:fact");
    activeRun.setParentRunId(91L);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitFactRefresh(config, 91L, true, "Mirror run completed");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
    assertThat(result.runId()).isEqualTo(104L);
    assertThat(result.type()).isEqualTo(SyncType.COMPENSATION);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_QUEUED);
    assertThat(result.message()).isEqualTo("当前镜像任务的事实刷新已提交，已复用现有任务。");
  }

  @Test
  void shouldQueueDistinctFactRefreshForAnotherMirrorParent() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(104L, SyncRunType.FACT_REFRESH, SyncRunStatus.RUNNING, "source:12:default:fact");
    activeRun.setParentRunId(88L);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(), List.of(activeRun));

    submissionService.submitFactRefresh(config, 91L, false, "Mirror run completed");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FACT_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getParentRunId()).isEqualTo(91L);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(12L);
    config.setSourceInstance("default");
    config.setSourceEnabled(true);
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_CPU_RATIO);
    config.setSyncThreadValue(new BigDecimal("0.8"));
    config.setMaxSyncThreads(16);
    return config;
  }

  private SyncRun activeRun(Long id, SyncRunType runType, SyncRunStatus status, String scope) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_existing_" + id);
    run.setConfigId(12L);
    run.setSourceInstance("default");
    run.setRunType(runType);
    run.setStatus(status);
    run.setExclusiveScope(scope);
    return run;
  }
}
