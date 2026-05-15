package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SyncRunDispatcherServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunWorkerService workerService;
  private SyncRunDispatcherService dispatcherService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    workerService = mock(SyncRunWorkerService.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setSchedulerEnabled(true);
    properties.setHeartbeatTimeoutSeconds(12);
    dispatcherService = new SyncRunDispatcherService(properties, jdbcTemplate, workerService);
  }

  @Test
  void shouldClaimHighestPriorityQueuedRunWithSkipLockedAndScopeGuard() {
    SyncRun claimed = queuedRun(21L, 100, "source:1:alpha:mirror");
    when(jdbcTemplate.queryForObject(any(String.class), any(RowMapper.class), eq("sync-dispatcher"), eq(12)))
        .thenReturn(claimed);

    SyncRun result = dispatcherService.claimNextQueuedRun("sync-dispatcher", 12);

    assertThat(result).isSameAs(claimed);
    verify(jdbcTemplate)
        .queryForObject(
            org.mockito.ArgumentMatchers.contains("for update skip locked"),
            any(RowMapper.class),
            eq("sync-dispatcher"),
            eq(12));
  }

  @Test
  void shouldDispatchClaimedRunWhenSchedulerEnabled() {
    SyncRun claimed = queuedRun(22L, 100, "source:1:alpha:mirror");
    when(jdbcTemplate.queryForObject(any(String.class), any(RowMapper.class), eq("sync-dispatcher"), eq(12)))
        .thenReturn(claimed);

    dispatcherService.runOnce();

    verify(workerService).executeRun(claimed);
  }

  @Test
  void shouldNotDispatchWhenSchedulerDisabled() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setSchedulerEnabled(false);
    dispatcherService = new SyncRunDispatcherService(properties, jdbcTemplate, workerService);

    dispatcherService.runOnce();

    verify(workerService, org.mockito.Mockito.never()).executeRun(org.mockito.ArgumentMatchers.any());
  }

  private SyncRun queuedRun(Long id, int priority, String scope) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_full_alpha_" + id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.FULL_SYNC);
    run.setTriggerType(SyncTriggerType.MANUAL);
    run.setStatus(SyncRunStatus.QUEUED);
    run.setPriority(priority);
    run.setExclusiveScope(scope);
    run.setCreatedAt(LocalDateTime.now());
    return run;
  }
}
