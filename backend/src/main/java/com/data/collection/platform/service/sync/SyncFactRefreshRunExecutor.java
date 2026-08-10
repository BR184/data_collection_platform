package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactProjectionTaskService;
import com.data.collection.platform.service.FactProjectionTaskWorkerService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMergeRequestCommitFactCapability;
import com.data.collection.platform.service.GitlabSourceSchemaGuard;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class SyncFactRefreshRunExecutor {
  private final GitlabConfigService configService;
  private final FactBuildTaskService factBuildTaskService;
  private final FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private final FactProjectionTaskService projectionTaskService;
  private final FactProjectionTaskWorkerService projectionTaskWorkerService;
  private final GitlabSourceSchemaGuard sourceSchemaGuard;
  private final GitlabMirrorProperties properties;
  private final JsonUtils jsonUtils;

  public SyncFactRefreshRunExecutor(
      GitlabConfigService configService,
      FactBuildTaskService factBuildTaskService,
      FactRefreshTaskWorkerService factRefreshTaskWorkerService,
      FactProjectionTaskService projectionTaskService,
      FactProjectionTaskWorkerService projectionTaskWorkerService,
      GitlabSourceSchemaGuard sourceSchemaGuard,
      GitlabMirrorProperties properties,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.factBuildTaskService = factBuildTaskService;
    this.factRefreshTaskWorkerService = factRefreshTaskWorkerService;
    this.projectionTaskService = projectionTaskService;
    this.projectionTaskWorkerService = projectionTaskWorkerService;
    this.sourceSchemaGuard = sourceSchemaGuard;
    this.properties = properties;
    this.jsonUtils = jsonUtils;
  }

  public Result execute(SyncRun run) {
    GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
    SyncRunPayload payload = payload(run);
    boolean full = payload.fullBuildEnabled() || payload.manualFullRebuildEnabled();
    if (full) {
      sourceSchemaGuard.verifyAllFactSources(run.getSourceInstance());
      if (GitlabMergeRequestCommitFactCapability.isEnabled(config)) {
        sourceSchemaGuard.verifyMergeRequestCommitFactSource(run.getSourceInstance());
      }
      factBuildTaskService.enqueueFullFactRefreshTasks(config, run.getId());
    }

    drainFactTasks(run, config, full);
    drainProjectionTasks(run);

    FactBuildTaskService.RunTaskSummary factSummary =
        factBuildTaskService.summarizeFactRun(run.getId());
    FactProjectionTaskService.RunTaskSummary projectionSummary =
        projectionTaskService.summarize(run.getId());
    int planned = factSummary.totalTasks() + projectionSummary.totalTasks();
    int completed = factSummary.successTasks() + projectionSummary.successTasks();
    if (factSummary.failedTasks() > 0 || projectionSummary.failedTasks() > 0) {
      return new Result(
          planned,
          completed,
          factSummary.affectedRows(),
          SyncRunStatus.FAILED,
          null,
          "事实或投影任务达到自动重试上限");
    }
    LocalDateTime retryAt = earlier(
        factSummary.nextRunAfter(), projectionSummary.nextRunAfter());
    if (factSummary.retryWaitingTasks() > 0 || projectionSummary.retryWaitingTasks() > 0) {
      return new Result(
          planned,
          completed,
          factSummary.affectedRows(),
          SyncRunStatus.RETRYING,
          retryAt,
          "事实或投影任务等待重试");
    }
    boolean unpublished = !full
        && factBuildTaskService.hasUnpublishedTargets(run.getConfigId(), run.getSourceInstance());
    if (unpublished || factSummary.hasActiveTasks() || projectionSummary.hasActiveTasks()) {
      return new Result(
          planned,
          completed,
          factSummary.affectedRows(),
          SyncRunStatus.PAUSED,
          LocalDateTime.now().plusSeconds(5),
          "等待持久事实目标继续收敛");
    }
    return new Result(
        planned,
        completed,
        factSummary.affectedRows(),
        SyncRunStatus.SUCCESS,
        null,
        null);
  }

  private void drainFactTasks(
      SyncRun run, GitlabSyncConfig config, boolean full) {
    int batchSize = Math.max(1, Math.min(1000, properties.getFactTargetBatchSize()));
    int assigned;
    do {
      assigned = full
          ? 0
          : factBuildTaskService.assignPendingSourceTargetBatches(
              config, run.getId(), batchSize);
      QueuedFactBuildTask task;
      while ((task =
              factBuildTaskService.claimNextQueuedTaskForFactRun(
                  run.getId(), "fact-run-" + run.getId(),
                  Math.max(1, properties.getHeartbeatTimeoutSeconds())))
          != null) {
        factRefreshTaskWorkerService.execute(task);
      }
    } while (!full && assigned > 0);
  }

  private void drainProjectionTasks(SyncRun run) {
    QueuedFactProjectionTask task;
    while ((task =
            projectionTaskService.claimNext(
                run.getId(),
                "projection-run-" + run.getId(),
                Math.max(1, properties.getHeartbeatTimeoutSeconds())))
        != null) {
      projectionTaskWorkerService.execute(task);
    }
  }

  private LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isBefore(right) ? left : right;
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
      LocalDateTime runAfter,
      String errorMessage) {
  }
}
