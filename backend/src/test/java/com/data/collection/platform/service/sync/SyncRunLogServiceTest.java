package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
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

class SyncRunLogServiceTest {
  private SyncRunMapper syncRunMapper;
  private JdbcTemplate jdbcTemplate;
  private SyncRunLogService logService;

  @BeforeEach
  void setUp() {
    syncRunMapper = org.mockito.Mockito.mock(SyncRunMapper.class);
    jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    logService = new SyncRunLogService(syncRunMapper, jdbcTemplate, new SyncRunPolicyService());
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
    run.setPlannedTableCount(6);
    run.setCompletedTableCount(6);
    run.setAppliedRows(120L);
    run.setStartedAt(LocalDateTime.of(2026, 5, 15, 10, 0));
    run.setFinishedAt(LocalDateTime.of(2026, 5, 15, 10, 5));
    when(syncRunMapper.selectList(any())).thenReturn(List.of(run));
    when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(31L)))
        .thenReturn("Run finished cleanly");

    List<Map<String, Object>> logs = logService.recentLogs(config, 10);

    assertThat(logs).hasSize(1);
    assertThat(logs.getFirst())
        .containsEntry("id", 31L)
        .containsEntry("runId", "sr_incremental_alpha")
        .containsEntry("syncType", "INCREMENTAL")
        .containsEntry("status", SyncStatus.SUCCESS.name())
        .containsEntry("message", "Run finished cleanly")
        .containsEntry("tableCount", 6)
        .containsEntry("recordCount", 120L);
  }
}
