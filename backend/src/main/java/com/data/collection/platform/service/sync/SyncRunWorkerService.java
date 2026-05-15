package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class SyncRunWorkerService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunTablePlanningService tablePlanningService;
  private final SyncRunTableWorkerService tableWorkerService;
  private final GitlabConfigService configService;

  public SyncRunWorkerService(
      SyncRunMapper syncRunMapper,
      SyncRunTablePlanningService tablePlanningService,
      SyncRunTableWorkerService tableWorkerService,
      GitlabConfigService configService) {
    this.syncRunMapper = syncRunMapper;
    this.tablePlanningService = tablePlanningService;
    this.tableWorkerService = tableWorkerService;
    this.configService = configService;
  }

  @Transactional
  public void executeRun(SyncRun run) {
    if (run == null || run.getId() == null) {
      return;
    }
    markRunning(run);
    try {
      if (run.getRunType() == SyncRunType.TABLE_REFRESH) {
        int planned = tablePlanningService.planRunTables(run.getId());
        int finished = tableWorkerService.drainRunTasks(run.getId());
        finishRun(run, SyncRunStatus.SUCCESS, planned, finished, null);
      } else {
        finishRun(run, SyncRunStatus.SUCCESS, 0, 0, null);
      }
      updateSyncTimestamps(run);
    } catch (Exception e) {
      finishRun(run, SyncRunStatus.FAILED, 0, 0, e.getMessage());
      log.error("Sync run failed, runId={}", run.getRunId(), e);
    }
  }

  private void markRunning(SyncRun run) {
    run.setStatus(SyncRunStatus.RUNNING);
    run.setStartedAt(run.getStartedAt() == null ? LocalDateTime.now() : run.getStartedAt());
    run.setHeartbeatAt(LocalDateTime.now());
    run.setUpdatedAt(LocalDateTime.now());
    syncRunMapper.updateById(run);
  }

  private void finishRun(SyncRun run, SyncRunStatus status, int planned, int finished, String errorMessage) {
    run.setStatus(status);
    run.setPlannedTableCount(planned);
    run.setCompletedTableCount(finished);
    run.setFinishedAt(LocalDateTime.now());
    run.setErrorMessage(errorMessage);
    run.setUpdatedAt(LocalDateTime.now());
    syncRunMapper.updateById(run);
  }

  private void updateSyncTimestamps(SyncRun run) {
    boolean fullSync = run.getRunType() == SyncRunType.FULL_SYNC;
    configService.updateSyncTime(run.getConfigId(), fullSync);
  }
}
