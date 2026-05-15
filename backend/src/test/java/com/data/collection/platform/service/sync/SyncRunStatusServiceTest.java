package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunStatusServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunLogService logService;
  private SyncRunStatusService statusService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    logService = org.mockito.Mockito.mock(SyncRunLogService.class);
    statusService = new SyncRunStatusService(syncRunMapper, jdbcTemplate, new SyncRunPolicyService(), logService);
  }

  @Test
  void shouldBuildRunningStatusFromSyncRunsAndTableTasks() {
    GitlabSyncConfig config = config();
    LocalDateTime startedAt = LocalDateTime.of(2026, 5, 15, 9, 30);
    SyncRun run = run(11L, "sr_full_alpha", SyncRunType.FULL_SYNC, SyncRunStatus.RUNNING, startedAt);
    when(syncRunMapper.selectList(any())).thenReturn(List.of(run));
    when(jdbcTemplate.queryForList(any(String.class), eq(11L)))
        .thenReturn(List.of(
            Map.of("status", "RUNNING", "count", 2L, "rows_scanned", 25L, "rows_applied", 20L),
            Map.of("status", "SUCCESS", "count", 3L, "rows_scanned", 70L, "rows_applied", 60L),
            Map.of("status", "FAILED", "count", 1L, "rows_scanned", 5L, "rows_applied", 0L)));
    when(jdbcTemplate.queryForList(any(String.class), eq(11L), eq(5)))
        .thenReturn(List.of(
            Map.of("source_table", "issues", "status", "RUNNING", "rows_applied", 10L),
            Map.of("source_table", "notes", "status", "RUNNING", "rows_applied", 10L)));
    when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq(11L))).thenReturn(0);

    MirrorStatusResponse response = statusService.getStatus(config);

    assertThat(response.currentStatus()).isEqualTo(SyncStatus.RUNNING);
    assertThat(response.currentStartedAt()).isEqualTo(startedAt);
    assertThat(response.currentMessage()).contains("sr_full_alpha");
    assertThat(response.currentTask())
        .containsEntry("id", 11L)
        .containsEntry("runId", "sr_full_alpha")
        .containsEntry("taskType", "FULL")
        .containsEntry("status", "RUNNING")
        .containsEntry("cancelRequested", false);
    assertThat(response.progress().getPhase()).isEqualTo("FULL_SYNC");
    assertThat(response.progress().getTotalTables()).isEqualTo(6);
    assertThat(response.progress().getCompletedTables()).isEqualTo(3);
    assertThat(response.progress().getSyncedRecords()).isEqualTo(80);
    assertThat(response.progress().getCurrentTable()).isEqualTo("issues, notes");
  }

  @Test
  void shouldReturnIdleWhenNoActiveRunExists() {
    GitlabSyncConfig config = config();
    when(syncRunMapper.selectList(any())).thenReturn(List.of());

    MirrorStatusResponse response = statusService.getStatus(config);

    assertThat(response.currentStatus()).isEqualTo(SyncStatus.IDLE);
    assertThat(response.currentTask()).isNull();
    assertThat(response.progress()).isNull();
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setName("alpha source");
    config.setSourceInstance("alpha");
    return config;
  }

  private SyncRun run(
      Long id,
      String runId,
      SyncRunType runType,
      SyncRunStatus status,
      LocalDateTime startedAt) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId(runId);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(runType);
    run.setTriggerType(SyncTriggerType.MANUAL);
    run.setStatus(status);
    run.setExclusiveScope("source:1:alpha:mirror");
    run.setCancelRequested(false);
    run.setCreatedAt(startedAt.minusMinutes(2));
    run.setStartedAt(startedAt);
    run.setPlannedTableCount(6);
    run.setCompletedTableCount(0);
    run.setAppliedRows(0L);
    return run;
  }
}
