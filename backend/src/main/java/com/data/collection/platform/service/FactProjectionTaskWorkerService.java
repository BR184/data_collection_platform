package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactPublicationContext;
import com.data.collection.platform.entity.FactPublicationMode;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 执行一项稳定范围投影预热；异常由同一持久任务重试。 */
@Service
public class FactProjectionTaskWorkerService {
  private final FactProjectionTaskService taskService;
  private final StatisticBoardSnapshotRefreshService statisticRefreshService;
  private final PageRecordSnapshotRefreshService recordRefreshService;
  private final FactProjectionExecutionContext executionContext;
  private final GitlabMirrorProperties properties;
  private final com.data.collection.platform.service.read.CandidateProjectionArtifactService artifactService;

  public FactProjectionTaskWorkerService(
      FactProjectionTaskService taskService,
      StatisticBoardSnapshotRefreshService statisticRefreshService,
      PageRecordSnapshotRefreshService recordRefreshService,
      FactProjectionExecutionContext executionContext,
      GitlabMirrorProperties properties,
      com.data.collection.platform.service.read.CandidateProjectionArtifactService artifactService) {
    this.taskService = taskService;
    this.statisticRefreshService = statisticRefreshService;
    this.recordRefreshService = recordRefreshService;
    this.executionContext = executionContext;
    this.properties = properties;
    this.artifactService = artifactService;
  }

  /** 执行候选真实业务预热；更高代际不能给当前任务记 no-op 成功，失败保留上一公开版本。 */
  public boolean execute(QueuedFactProjectionTask task) {
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(task)) {
      requireLease(task);
      FactPublicationContext context = artifactService.contextForTask(task);
      java.util.List<String> consumers = new java.util.ArrayList<>(
          statisticRefreshService.refreshAfterFactBuild(context, () -> requireLease(task)));
      consumers.addAll(recordRefreshService.refreshAfterFactBuild(context, () -> requireLease(task)));
      requireLease(task);
      artifactService.complete(task, context, consumers, executionContext.writtenArtifacts());
      return true;
    } catch (ProjectionTaskSupersededException error) {
      return false;
    } catch (ProjectionTaskLeaseLostException error) {
      return false;
    } catch (Exception error) {
      try {
        taskService.failOwned(task, error.getMessage());
      } catch (ProjectionTaskLeaseLostException ignored) {
        return false;
      }
      return false;
    }
  }

  private void requireLease(QueuedFactProjectionTask task) {
    if (!taskService.renewOwned(task, Math.max(1, properties.getHeartbeatTimeoutSeconds()))) {
      throw new ProjectionTaskLeaseLostException(task.id());
    }
  }
}
