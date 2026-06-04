package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunSubmissionServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunSubmissionService submissionService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    submissionService =
        new SyncRunSubmissionService(
            syncRunMapper,
            new SyncRunPolicyService(),
            jdbcTemplate,
            new JsonUtils(new ObjectMapper()),
            new SyncThreadBudgetResolver(properties));
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
    assertThat(saved.getSourceInstance()).isEqualTo("source_a");
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FULL_SYNC);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getPriority()).isEqualTo(100);
    assertThat(saved.getExclusiveScope()).isEqualTo("source:12:source_a:mirror");
    assertThat(saved.getThreadMode()).isEqualTo(SyncThreadBudgetResolver.MODE_CPU_RATIO);
    assertThat(saved.getThreadValue()).isEqualByComparingTo(new BigDecimal("0.8"));
    assertThat(saved.getRunId()).startsWith("sr_fs_source_a_");
    assertThat(saved.getRunId()).hasSizeLessThanOrEqualTo(64);
    assertThat(result.runId()).isEqualTo(saved.getId());
    assertThat(result.type()).isEqualTo(SyncType.FULL);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
    verify(jdbcTemplate).queryForObject(
        contains("pg_advisory_xact_lock"), eq(Object.class), eq("source:12:source_a:mirror"));
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
    assertThat(saved.getRunId()).startsWith("sr_is_jitter_smoke_");
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
    assertThat(saved.getRunId()).startsWith("sr_is_source_instance_with_a_");
  }

  @Test
  void shouldReuseActiveFullSyncInsteadOfCreatingAnotherOne() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(77L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitFullSync(config, "Manual full sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FULL_SYNC);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.MERGED);
    assertThat(saved.getParentRunId()).isEqualTo(77L);
    assertThat(saved.getFinishedAt()).isNotNull();
    assertThat(saved.getPayloadJson()).contains("\"absorbedAction\":\"REUSED_ACTIVE\"");
    verify(jdbcTemplate, never()).update(
        ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    assertThat(result.runId()).isEqualTo(77L);
    assertThat(result.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_ACTIVE);
  }

  @Test
  void shouldMergeTableRefreshIntoActiveFullSync() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(91L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues", "labels"), "Need refresh");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.TABLE_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.MERGED);
    assertThat(saved.getParentRunId()).isEqualTo(91L);
    assertThat(saved.getRequestReason()).isEqualTo("Need refresh");
    assertThat(saved.getPlannedTableCount()).isEqualTo(2);
    assertThat(saved.getCompletedTableCount()).isZero();
    assertThat(saved.getFinishedAt()).isNotNull();
    assertThat(saved.getPayloadJson())
        .contains("\"sourceTables\":[\"issues\",\"labels\"]")
        .contains("\"primaryTableName\":\"issues\"")
        .contains("\"parentRunId\":91")
        .contains("\"parentRunRunId\":\"sr_existing_91\"")
        .contains("\"absorbedAction\":\"DEDUPED\"");
    verify(jdbcTemplate, never())
        .update(
            ArgumentMatchers.contains("insert into sync_run_events"),
            ArgumentMatchers.<Object[]>any());
    assertThat(result.runId()).isEqualTo(91L);
    assertThat(result.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.DEDUPED);
    assertThat(result.message()).isEqualTo("本次刷新请求已合并到同一数据源正在执行的同步任务中。");
  }

  @Test
  void shouldQueueTableRefreshBehindActiveFullCompensationInsteadOfDeduplicating() {
    GitlabSyncConfig config = config();
    SyncRun activeRun =
        activeRun(92L, SyncRunType.FULL_COMPENSATION_SCAN, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues"), "Need refresh");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.TABLE_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(saved.getPriority()).isEqualTo(40);
    assertThat(saved.getExclusiveScope()).isEqualTo("source:12:source_a:mirror");
    assertThat(saved.getPayloadJson()).contains("\"sourceTables\":[\"issues\"]");
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
  }

  @Test
  void shouldDetectActiveFullCompensationRunForSameSource() {
    GitlabSyncConfig config = config();
    SyncRun activeRun =
        activeRun(93L, SyncRunType.FULL_COMPENSATION_SCAN, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    assertThat(submissionService.hasActiveFullCompensationRun(config)).isTrue();

    verify(syncRunMapper).selectList(any());
  }

  @Test
  void shouldDeduplicateIncrementalWhenMirrorRunAlreadyExists() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(101L, SyncRunType.TABLE_REFRESH, SyncRunStatus.QUEUED, "source:12:source_a:mirror");
    activeRun.setPayloadJson("{\"sourceTables\":[\"issues\"]}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitIncrementalSync(config, null, "Manual incremental sync");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.INCREMENTAL_SYNC);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.MERGED);
    assertThat(saved.getParentRunId()).isEqualTo(101L);
    assertThat(saved.getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(saved.getFinishedAt()).isNotNull();
    assertThat(saved.getPayloadJson()).contains("\"absorbedAction\":\"DEDUPED\"");
    assertThat(result.runId()).isEqualTo(101L);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.DEDUPED);
  }

  @Test
  void shouldDeduplicateSameTableRefreshWhenAlreadyQueued() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(102L, SyncRunType.TABLE_REFRESH, SyncRunStatus.QUEUED, "source:12:source_a:mirror");
    activeRun.setPayloadJson("{\"sourceTables\":[\"issues\",\"notes\"]}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitTableRefresh(config, List.of("Issues", "notes"), "Board refresh");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.TABLE_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.MERGED);
    assertThat(saved.getParentRunId()).isEqualTo(102L);
    assertThat(saved.getPayloadJson()).contains("\"sourceTables\":[\"issues\",\"notes\"]");
    assertThat(result.runId()).isEqualTo(102L);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.DEDUPED);
  }

  @Test
  void shouldMergeQueuedLowerPriorityMirrorRunsWhenFullSyncIsSubmitted() {
    GitlabSyncConfig config = config();
    SyncRun queuedRefresh = activeRun(103L, SyncRunType.TABLE_REFRESH, SyncRunStatus.QUEUED, "source:12:source_a:mirror");
    queuedRefresh.setPayloadJson("{\"sourceTables\":[\"issues\"]}");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(queuedRefresh));

    submissionService.submitFullSync(config, "Manual full sync");

    verify(jdbcTemplate).update(
        contains("status = 'MERGED'"),
        any(),
        any(),
        eq(12L),
        eq("source_a"),
        eq("source:12:source_a:mirror"),
        eq(100));
    verify(syncRunMapper).insert(any(SyncRun.class));
  }

  @Test
  void shouldReuseActiveFactRefreshForSameSource() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(104L, SyncRunType.FACT_REFRESH, SyncRunStatus.QUEUED, "source:12:source_a:fact");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitFactRefresh(config, 91L, true, "Mirror run completed");

    ArgumentCaptor<SyncRun> runCaptor = ArgumentCaptor.forClass(SyncRun.class);
    verify(syncRunMapper).insert(runCaptor.capture());
    SyncRun saved = runCaptor.getValue();
    assertThat(saved.getRunType()).isEqualTo(SyncRunType.FACT_REFRESH);
    assertThat(saved.getStatus()).isEqualTo(SyncRunStatus.MERGED);
    assertThat(saved.getParentRunId()).isEqualTo(104L);
    assertThat(saved.getPayloadJson()).contains("\"parentRunId\":104");
    assertThat(result.runId()).isEqualTo(104L);
    assertThat(result.type()).isEqualTo(SyncType.COMPENSATION);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_QUEUED);
    assertThat(result.message()).isEqualTo("事实刷新已在队列中或正在执行，已复用现有任务。");
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(12L);
    config.setSourceInstance("source_a");
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
    run.setSourceInstance("source_a");
    run.setRunType(runType);
    run.setStatus(status);
    run.setExclusiveScope(scope);
    return run;
  }
}
