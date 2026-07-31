package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.PageRecordSnapshotRefreshService;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService;
import org.springframework.stereotype.Service;

@Service
public class SyncFactRefreshRunExecutor {
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final FactBuildTaskService factBuildTaskService;
  private final FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private final StatisticBoardSnapshotRefreshService snapshotRefreshService;
  private final PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService;
  private final JsonUtils jsonUtils;

  public SyncFactRefreshRunExecutor(
      GitlabConfigService configService,
      FactBuildService factBuildService,
      FactBuildTaskService factBuildTaskService,
      FactRefreshTaskWorkerService factRefreshTaskWorkerService,
      StatisticBoardSnapshotRefreshService snapshotRefreshService,
      PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.factBuildTaskService = factBuildTaskService;
    this.factRefreshTaskWorkerService = factRefreshTaskWorkerService;
    this.snapshotRefreshService = snapshotRefreshService;
    this.pageRecordSnapshotRefreshService = pageRecordSnapshotRefreshService;
    this.jsonUtils = jsonUtils;
  }

  public Result execute(SyncRun run) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    SyncRunPayload payload = payload(run);
    if (payload.manualFullRebuildEnabled()) {
      return executeManualFullRebuild(run, config);
    }
    boolean full = payload.fullBuildEnabled();
    int planned = factBuildTaskService.enqueueFactRefreshTasks(config, full, run.getId());
    int completed = 0;
    long affectedRows = 0L;
    QueuedFactBuildTask task;
    while ((task =
            factBuildTaskService.claimNextQueuedTaskForFactRun(
                run.getId(), "fact-run-worker", 30))
        != null) {
      FactBuildResponse response = factRefreshTaskWorkerService.execute(task);
      if (response != null) {
        completed++;
        affectedRows += response.affectedRows();
      }
    }
    SyncRunStatus status = completed < planned ? SyncRunStatus.PARTIAL_SUCCESS : SyncRunStatus.SUCCESS;
    return new Result(
        planned,
        completed,
        affectedRows,
        status,
        status == SyncRunStatus.SUCCESS ? null : "部分事实数据刷新任务未完成");
  }

  private Result executeManualFullRebuild(SyncRun run, GitlabSyncConfig config) {
    FactBuildResponse response = factBuildService.rebuildAllFactsForConfig(config, true, run.getId());
    snapshotRefreshService.refreshAfterFactBuild("ALL", true);
    pageRecordSnapshotRefreshService.refreshAfterFactBuild("ALL", true);
    return new Result(1, 1, response.affectedRows(), SyncRunStatus.SUCCESS, null);
  }

  private SyncRunPayload payload(SyncRun run) {
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    return payload == null ? SyncRunPayload.empty() : payload;
  }

  public record Result(
      int plannedTasks,
      int completedTasks,
      long affectedRows,
      SyncRunStatus status,
      String errorMessage) {
  }
}
