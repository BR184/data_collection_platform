package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import org.springframework.stereotype.Service;

@Service
public class SyncFactRefreshRunExecutor {
  private final GitlabConfigService configService;
  private final FactBuildTaskService factBuildTaskService;
  private final FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private final JsonUtils jsonUtils;

  public SyncFactRefreshRunExecutor(
      GitlabConfigService configService,
      FactBuildTaskService factBuildTaskService,
      FactRefreshTaskWorkerService factRefreshTaskWorkerService,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.factBuildTaskService = factBuildTaskService;
    this.factRefreshTaskWorkerService = factRefreshTaskWorkerService;
    this.jsonUtils = jsonUtils;
  }

  public Result execute(SyncRun run) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    boolean full = fullBuild(run);
    int planned = factBuildTaskService.enqueueMirrorRefreshTasks(config, full, run.getId());
    int completed = 0;
    long affectedRows = 0L;
    QueuedFactBuildTask task;
    while ((task = factBuildTaskService.claimNextQueuedTaskForRun(run.getId(), "fact-run-worker", 30)) != null) {
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

  private boolean fullBuild(SyncRun run) {
    SyncRunPayload payload = jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    return payload != null && payload.fullBuildEnabled();
  }

  public record Result(
      int plannedTasks,
      int completedTasks,
      long affectedRows,
      SyncRunStatus status,
      String errorMessage) {
  }
}
