package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunYieldServiceTest {
  private JdbcTemplate jdbcTemplate;
  private SyncRunYieldService yieldService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    yieldService = new SyncRunYieldService(jdbcTemplate);
  }

  @Test
  void shouldYieldFullCompensationForQueuedIncrementalOrTableRefresh() {
    SyncRun run = run(SyncRunType.FULL_COMPENSATION_SCAN);
    when(jdbcTemplate.queryForObject(
            contains("run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH')"),
            eq(Integer.class),
            eq(41L),
            eq("source:1:default:mirror")))
        .thenReturn(1);

    assertThat(yieldService.shouldYield(run)).isTrue();
  }

  @Test
  void shouldNotYieldIncrementalOrSystemHookRun() {
    assertThat(yieldService.shouldYield(run(SyncRunType.INCREMENTAL_SYNC))).isFalse();
    assertThat(yieldService.shouldYield(run(SyncRunType.SYSTEM_HOOK))).isFalse();

    verify(jdbcTemplate, never()).queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void shouldYieldIncrementalOnlyAfterReconciliationForQueuedTableRefresh() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    SyncRunTableTask reconciliationTask = task(SyncRunTableTaskStage.RECONCILE);
    when(jdbcTemplate.queryForObject(
            contains("waiting.run_type = 'TABLE_REFRESH'"),
            eq(Integer.class),
            eq(41L),
            eq("source:1:default:mirror")))
        .thenReturn(1);

    assertThat(yieldService.shouldYieldAfterTableTask(run, reconciliationTask)).isTrue();
    assertThat(yieldService.shouldYieldAfterTableTask(run, task(SyncRunTableTaskStage.SCAN)))
        .isFalse();
  }

  @Test
  void shouldPersistPauseOnlyWhenForegroundWaiterStillExists() {
    SyncRun run = run(SyncRunType.FULL_SYNC);
    when(jdbcTemplate.update(
            contains("set status = 'PAUSED'"), eq(41L), eq("run-owner-41")))
        .thenReturn(1);

    assertThat(yieldService.pauseIfRequested(run)).isTrue();
    assertThat(run.getStatus())
        .isEqualTo(com.data.collection.platform.entity.sync.SyncRunStatus.PAUSED);
  }

  @Test
  void shouldPersistIncrementalPauseOnlyForQueuedTableRefresh() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC);
    when(jdbcTemplate.update(
            contains("waiting.run_type = 'TABLE_REFRESH'"), eq(41L), eq("run-owner-41")))
        .thenReturn(1);

    assertThat(yieldService.pauseIfRequested(run)).isTrue();
    assertThat(run.getStatus())
        .isEqualTo(com.data.collection.platform.entity.sync.SyncRunStatus.PAUSED);
  }

  private SyncRunTableTask task(SyncRunTableTaskStage stage) {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setTaskStage(stage);
    return task;
  }

  private SyncRun run(SyncRunType runType) {
    SyncRun run = new SyncRun();
    run.setId(41L);
    run.setLeaseOwner("run-owner-41");
    run.setRunType(runType);
    run.setExclusiveScope("source:1:default:mirror");
    return run;
  }
}
