package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import java.net.InetAddress;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FactRefreshTaskWorkerService {
  private static final String WORKER_ID = resolveWorkerId();

  private final FactBuildTaskService taskService;
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final IntegrationTestFactBuildService integrationTestFactBuildService;
  private final GitlabMirrorProperties properties;
  private final FactTargetPublicationService targetPublicationService;
  private final SyncFactPublicationStateService publicationStateService;
  private final SyncRunEventRecorder eventRecorder;

  public FactRefreshTaskWorkerService(
      FactBuildTaskService taskService,
      GitlabConfigService configService,
      FactBuildService factBuildService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      GitlabMirrorProperties properties,
      FactTargetPublicationService targetPublicationService,
      SyncFactPublicationStateService publicationStateService,
      SyncRunEventRecorder eventRecorder) {
    this.taskService = taskService;
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.properties = properties;
    this.targetPublicationService = targetPublicationService;
    this.publicationStateService = publicationStateService;
    this.eventRecorder = eventRecorder;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    taskService.recoverTimedOutQueuedTasks();
    QueuedFactBuildTask task =
        taskService.claimNextQueuedTask(WORKER_ID, Math.max(1, properties.getHeartbeatTimeoutSeconds()));
    if (task == null) {
      return;
    }
    execute(task);
  }

  public FactBuildResponse execute(QueuedFactBuildTask task) {
    try {
      GitlabSyncConfig config = configService.getConfigById(task.configId());
      FactType factType = parseFactType(task.factType());
      if (!publicationStateService.isReady(task.sourceInstance(), factType)) {
        throw new IllegalStateException("事实来源依赖代际尚未就绪：" + factType.name());
      }
      FactBuildResponse response = task.full()
          ? targetPublicationService.publishFull(
              task, progress -> rebuildFullFacts(config, factType, progress))
          : targetPublicationService.publish(
              task, rootIds -> rebuildTargetedFacts(task, factType, rootIds));
      return response;
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

  private static String resolveWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName() + "-fact-" + UUID.randomUUID();
    } catch (Exception ignored) {
      return "fact-refresh-worker-" + UUID.randomUUID();
    }
  }
}
