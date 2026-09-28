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

  public FactProjectionTaskWorkerService(
      FactProjectionTaskService taskService,
      StatisticBoardSnapshotRefreshService statisticRefreshService,
      PageRecordSnapshotRefreshService recordRefreshService,
      FactProjectionExecutionContext executionContext,
      GitlabMirrorProperties properties) {
    this.taskService = taskService;
    this.statisticRefreshService = statisticRefreshService;
    this.recordRefreshService = recordRefreshService;
    this.executionContext = executionContext;
    this.properties = properties;
  }

  /** 执行已领取任务；旧 generation 任务以 superseded no-op 成功。 */
  public boolean execute(QueuedFactProjectionTask task) {
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(task)) {
      requireLease(task);
      long currentGeneration = taskService.currentGeneration(task.scope());
      if (currentGeneration > task.targetGeneration()) {
        taskService.finishOwned(task, "投影范围已有更高 generation");
        return true;
      }
      if (currentGeneration < task.targetGeneration()) {
        throw new IllegalStateException("投影任务目标 generation 尚未提交");
      }
      FactPublicationMode mode =
          task.scope().scopeType() == ProjectionScopeType.FULL_EPOCH
              ? FactPublicationMode.FULL
              : FactPublicationMode.TARGETED;
      FactPublicationContext context =
          new FactPublicationContext(
              task.scope().sourceInstance(),
              task.scope().factType(),
              mode,
              Set.of(task.scope()));
      statisticRefreshService.refreshAfterFactBuild(context, () -> requireLease(task));
      recordRefreshService.refreshAfterFactBuild(context, () -> requireLease(task));
      requireLease(task);
      taskService.finishOwned(task, "投影范围刷新成功");
      return true;
    } catch (ProjectionTaskSupersededException error) {
      try {
        taskService.finishOwned(task, "投影范围已有更高 generation");
        return true;
      } catch (ProjectionTaskLeaseLostException leaseLost) {
        return false;
      }
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
