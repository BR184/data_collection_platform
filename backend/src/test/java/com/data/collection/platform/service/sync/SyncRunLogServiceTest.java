package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.common.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunLogServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunLogService logService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    logService =
        new SyncRunLogService(
            syncRunMapper, jdbcTemplate, new SyncRunPolicyService(), new JsonUtils(new ObjectMapper()));
  }

  @Test
  void shouldBuildRecentRunLogsFromSyncRunsAndEvents() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    SyncRun run = new SyncRun();
    run.setId(31L);
    run.setRunId("sr_incremental_alpha");
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.INCREMENTAL_SYNC);
    run.setStatus(SyncRunStatus.SUCCESS);
    run.setRequestReason("Manual incremental sync");
    run.setPayloadJson(
        """
        {
          "syncType": "INCREMENTAL",
          "triggerType": "MANUAL",
          "reason": "system-test-defect-summary",
          "sourcePageKey": "system-test-defect-summary",
          "triggerSurface": "STATISTIC_BOARD",
          "sourceTables": ["issues", "notes"],
          "primaryTableName": "issues"
        }
        """);
    run.setPlannedTableCount(6);
    run.setCompletedTableCount(6);
    run.setAppliedRows(120L);
    run.setStartedAt(LocalDateTime.of(2026, 5, 15, 10, 0));
    run.setFinishedAt(LocalDateTime.of(2026, 5, 15, 10, 5));
    when(syncRunMapper.selectList(any())).thenReturn(List.of(run));
    when(jdbcTemplate.queryForObject(contains("from sync_run_table_tasks"), any(RowMapper.class), eq(31L)))
        .thenAnswer(invocation -> {
          RowMapper<?> mapper = invocation.getArgument(1);
          ResultSet rs = mock(ResultSet.class);
          when(rs.getInt("total_tasks")).thenReturn(42);
          when(rs.getInt("completed_tasks")).thenReturn(39);
          return mapper.mapRow(rs, 0);
        });
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(31L)))
        .thenReturn("Run finished cleanly");

    List<Map<String, Object>> logs = logService.recentLogs(config, 10);

    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst())
        .containsEntry("id", 31L)
        .containsEntry("runId", "sr_incremental_alpha")
        .containsEntry("syncType", "INCREMENTAL")
        .containsEntry("runType", "INCREMENTAL_SYNC")
        .containsEntry("triggerType", "MANUAL")
        .containsEntry("requestReason", "Manual incremental sync")
        .containsEntry("sourcePageKey", "system-test-defect-summary")
        .containsEntry("triggerSurface", "STATISTIC_BOARD")
        .containsEntry("sourceTables", List.of("issues", "notes"))
        .containsEntry("primaryTableName", "issues")
        .containsEntry("status", SyncStatus.SUCCESS.name())
        .containsEntry("message", "Run finished cleanly")
        .containsEntry("tableCount", 42)
        .containsEntry("completedTableCount", 39)
        .containsEntry("recordCount", 120L);
  }

  @Test
  void shouldExposeMergedRunMetadata() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    SyncRun run = new SyncRun();
    run.setId(32L);
    run.setRunId("sr_tr_alpha");
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.TABLE_REFRESH);
    run.setStatus(SyncRunStatus.MERGED);
    run.setParentRunId(31L);
    run.setPayloadJson(
        """
        {
          "triggerType": "MANUAL",
          "sourcePageKey": "issue-search",
          "sourceTables": ["issues"],
          "primaryTableName": "issues",
          "parentRunId": 31,
          "parentRunRunId": "sr_incremental_alpha"
        }
        """);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(run));
    when(jdbcTemplate.queryForObject(contains("from sync_run_table_tasks"), any(RowMapper.class), eq(32L)))
        .thenAnswer(invocation -> {
          RowMapper<?> mapper = invocation.getArgument(1);
          ResultSet rs = mock(ResultSet.class);
          when(rs.getInt("total_tasks")).thenReturn(0);
          when(rs.getInt("completed_tasks")).thenReturn(0);
          return mapper.mapRow(rs, 0);
        });

    List<Map<String, Object>> logs = logService.recentLogs(config, 10);

    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst())
        .containsEntry("runStatus", SyncRunStatus.MERGED.name())
        .containsEntry("parentRunId", 31L)
        .containsEntry("parentRunRunId", "sr_incremental_alpha")
        .containsEntry("sourcePageKey", "issue-search");
  }
}
