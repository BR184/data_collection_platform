package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
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
    assertThat(saved.getRunId()).startsWith("sr_full_sync_source_a_");
    assertThat(result.runId()).isEqualTo(saved.getId());
    assertThat(result.type()).isEqualTo(SyncType.FULL);
    assertThat(result.status()).isEqualTo(SyncStatus.QUEUED);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.QUEUED);
  }

  @Test
  void shouldReuseActiveFullSyncInsteadOfCreatingAnotherOne() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(77L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result = submissionService.submitFullSync(config, "Manual full sync");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
    verify(jdbcTemplate, never()).update(
        ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    assertThat(result.runId()).isEqualTo(77L);
    assertThat(result.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_ACTIVE);
    assertThat(result.message()).contains("复用现有运行单元");
  }

  @Test
  void shouldMergeTableRefreshIntoActiveFullSync() {
    GitlabSyncConfig config = config();
    SyncRun activeRun = activeRun(91L, SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, "source:12:source_a:mirror");
    when(syncRunMapper.selectList(any())).thenReturn(List.of(activeRun));

    var result =
        submissionService.submitTableRefresh(config, List.of("Issues", "labels"), "Need refresh");

    verify(syncRunMapper, never()).insert(any(SyncRun.class));
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(
        sqlCaptor.capture(),
        eq(91L),
        eq(12L),
        eq("source_a"),
        eq("TABLE_REFRESH_MERGED"),
        eq("issues"),
        eq("已合并到当前全量同步，完成后将以全量结果为准。"),
        ArgumentMatchers.anyString(),
        ArgumentMatchers.any());
    assertThat(sqlCaptor.getValue()).contains("insert into sync_run_events");
    assertThat(result.runId()).isEqualTo(91L);
    assertThat(result.status()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.DEDUPED);
    assertThat(result.message()).isEqualTo("已合并到当前全量同步，完成后将以全量结果为准。");
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
