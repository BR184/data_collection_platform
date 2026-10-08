package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FactRefreshTaskWorkerService {
  /** 依赖未就绪时的等待步长：复用既有 5 秒派发节奏，不新增运维配置。 */
  private static final int DEPENDENCY_DEFER_SECONDS = 5;

  private final FactBuildTaskService taskService;
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final IntegrationTestFactBuildService integrationTestFactBuildService;
  private final GitlabMirrorProperties properties;
  private final FactTargetPublicationService targetPublicationService;
  private final SyncFactPublicationStateService publicationStateService;
  private final SyncRunEventRecorder eventRecorder;
  private final FactTaskExecutionContext executionContext;

  public FactRefreshTaskWorkerService(
      FactBuildTaskService taskService,
      GitlabConfigService configService,
      FactBuildService factBuildService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      GitlabMirrorProperties properties,
      FactTargetPublicationService targetPublicationService,
      SyncFactPublicationStateService publicationStateService,
      SyncRunEventRecorder eventRecorder,
      FactTaskExecutionContext executionContext) {
    this.taskService = taskService;
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.properties = properties;
    this.targetPublicationService = targetPublicationService;
    this.publicationStateService = publicationStateService;
    this.eventRecorder = eventRecorder;
    this.executionContext = executionContext;
  }

  /**
   * 每轮巡检：回收租约超时任务，并把失去父运行授权的遗留任务转为人工待处理。
   *
   * <p>自动执行只由持有活动 {@code FACT_REFRESH} 运行的事实刷新执行器承载；本巡检不认领、不执行
   * 任何任务，因此不会再出现"页面看不到运行、后台却在构建事实"的执行路径。执行入口
   * {@link #execute(QueuedFactBuildTask)} 仍由运行执行器调用。
   */
  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    taskService.recoverTimedOutQueuedTasks();
    taskService.parkOrphanedTasksForManualDecision();
  }

  /**
   * 在事实任务身份下执行一次构建与发布。
   *
   * <p>身份在整段构建期间有效，使批次提交边界能够验证父运行与任务两层执行权；执行权在构建途中被
   * 撤销或转移时，本次执行立即停止且不消耗任务的重试预算。
   *
   * @param task 已领取且归属活动事实运行的任务
   * @return 构建结果；执行权失效时返回 {@code null}
   */
  public FactBuildResponse execute(QueuedFactBuildTask task) {
    try (FactTaskExecutionContext.Scope ignored = executionContext.open(task)) {
      GitlabSyncConfig config = configService.getConfigById(task.configId());
      FactType factType = parseFactType(task.factType());
      if (!publicationStateService.isReady(task.sourceInstance(), factType)) {
        taskService.deferOwnedTask(task, DEPENDENCY_DEFER_SECONDS);
        return null;
      }
      FactBuildResponse response = task.full()
          ? targetPublicationService.publishFull(
              task, progress -> rebuildFullFacts(config, factType, progress))
          : targetPublicationService.publish(
              task, rootIds -> rebuildTargetedFacts(task, factType, rootIds));
      return response;
    } catch (FactTaskLeaseLostException error) {
      log.info(
          "Fact refresh task stopped after losing execution rights, taskId={}, reason={}",
          task.id(),
          error.getMessage());
      return null;
    } catch (Exception e) {
      try {
        FactBuildTaskService.FailureDisposition disposition =
            taskService.failOwnedTask(task, e.getMessage());
        if (disposition.retryWaiting()) {
          eventRecorder.record(
              task.factRunId(),
              task.configId(),
              task.sourceInstance(),
              "FACT_BUILD_RETRY",
              "事实构建失败，已安排自动重试：" + truncateReason(e.getMessage()));
        }
      } catch (Exception leaseError) {
        log.warn("Fact refresh failure could not update owned task, taskId={}", task.id(), leaseError);
      }
      log.warn("Fact refresh task failed, taskId={}, factType={}", task.id(), task.factType(), e);
      return null;
    }
  }

  private String truncateReason(String message) {
    if (message == null || message.isBlank()) {
      return "无错误信息";
    }
    String normalized = message.strip().replaceAll("[\\r\\n\\t]", " ");
    return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "…";
  }

  private FactType parseFactType(String factType) {
    String normalized = factType == null ? "" : factType.trim().toUpperCase(Locale.ROOT);
    try {
      return FactType.valueOf(normalized);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Unsupported fact refresh type: " + normalized, error);
    }
  }

  private FactBuildResponse rebuildFullFacts(
      GitlabSyncConfig config, FactType factType, FactBuildProgress progress) {
    return switch (factType) {
      case ISSUE -> factBuildService.rebuildIssueFactsForQueuedTask(config, true, progress);
      case MERGE_REQUEST ->
          factBuildService.rebuildMergeRequestFactsForQueuedTask(config, true, progress);
      case INTEGRATION_TEST -> {
        FactBuildResponse response = integrationTestFactBuildService.rebuildFactsForConfig(config, true);
        progress.chunkCommitted(1, 1, response.affectedRows());
        yield response;
      }
    };
  }

  private FactBuildResponse rebuildTargetedFacts(
      QueuedFactBuildTask task, FactType factType, java.util.List<Long> rootIds) {
    return switch (factType) {
      case ISSUE -> factBuildService.rebuildIssueFactsByRootIds(task.sourceInstance(), rootIds);
      case MERGE_REQUEST ->
          factBuildService.rebuildMergeRequestFactsByRootIds(task.sourceInstance(), rootIds);
      case INTEGRATION_TEST ->
          integrationTestFactBuildService.rebuildFactsByRootIds(task.sourceInstance(), rootIds);
    };
  }
}
