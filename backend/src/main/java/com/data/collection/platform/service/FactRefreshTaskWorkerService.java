package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService;
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
  private final FactRefreshImpactScopeService impactScopeService;
  private final GitlabMirrorProperties properties;
  private final StatisticBoardSnapshotRefreshService snapshotRefreshService;
  private final PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService;
  private final FactPublicationTransaction publicationTransaction;

  public FactRefreshTaskWorkerService(
      FactBuildTaskService taskService,
      GitlabConfigService configService,
      FactBuildService factBuildService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      FactRefreshImpactScopeService impactScopeService,
      GitlabMirrorProperties properties,
      StatisticBoardSnapshotRefreshService snapshotRefreshService,
      PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService,
      FactPublicationTransaction publicationTransaction) {
    this.taskService = taskService;
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.impactScopeService = impactScopeService;
    this.properties = properties;
    this.snapshotRefreshService = snapshotRefreshService;
    this.pageRecordSnapshotRefreshService = pageRecordSnapshotRefreshService;
    this.publicationTransaction = publicationTransaction;
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
      String factType = normalizeFactType(task.factType());
      FactBuildResponse response = publicationTransaction.execute(() -> switch (factType) {
          case "ISSUE" -> rebuildIssueFacts(task, config, factType);
          case "MERGE_REQUEST" -> rebuildMergeRequestFacts(task, config, factType);
          case "INTEGRATION_TEST" -> rebuildIntegrationTestFacts(task, config, factType);
          default -> throw new IllegalArgumentException(
              "Unsupported fact refresh type: " + task.factType());
        });
      taskService.finishQueuedTask(task.id(), "SUCCESS", response.affectedRows(), response.message(), null);
      snapshotRefreshService.refreshAfterFactBuild(factType, response.full());
      pageRecordSnapshotRefreshService.refreshAfterFactBuild(factType, response.full());
      return response;
    } catch (Exception e) {
      taskService.finishQueuedTask(task.id(), "FAILED", 0, "事实数据刷新失败", e.getMessage());
      log.warn("Fact refresh task failed, taskId={}, factType={}", task.id(), task.factType(), e);
      return null;
    }
  }

  private String normalizeFactType(String factType) {
    return factType == null ? "" : factType.trim().toUpperCase(Locale.ROOT);
  }

  private FactBuildResponse rebuildIssueFacts(QueuedFactBuildTask task, GitlabSyncConfig config, String factType) {
    if (task.full()) {
      return factBuildService.rebuildIssueFactsForQueuedTask(config, true);
    }
    if (!taskService.hasSuccessfulFullBuild(task.sourceInstance(), factType)) {
      taskService.markQueuedTaskFullBuild(task.id());
      return factBuildService.rebuildIssueFactsForQueuedTask(config, true);
    }
    FactRefreshImpactScopeService.ImpactScope scope =
        impactScopeService.resolve(task.mirrorRunId(), task.sourceInstance(), factType);
    if (scope.fallbackRequired()) {
      return factBuildService.rebuildIssueFactsForQueuedTask(config, false);
    }
    if (scope.targets().isEmpty()) {
      return factBuildService.reconcileMissingIssueFacts(task.sourceInstance());
    }
    return factBuildService.rebuildIssueFactsByTargets(task.sourceInstance(), scope.targets());
  }

  private FactBuildResponse rebuildMergeRequestFacts(QueuedFactBuildTask task, GitlabSyncConfig config, String factType) {
    if (task.full()) {
      return factBuildService.rebuildMergeRequestFactsForQueuedTask(config, true);
    }
    if (!taskService.hasSuccessfulFullBuild(task.sourceInstance(), factType)) {
      taskService.markQueuedTaskFullBuild(task.id());
      return factBuildService.rebuildMergeRequestFactsForQueuedTask(config, true);
    }
    FactRefreshImpactScopeService.ImpactScope scope =
        impactScopeService.resolve(task.mirrorRunId(), task.sourceInstance(), factType);
    if (scope.fallbackRequired()) {
      return factBuildService.rebuildMergeRequestFactsForQueuedTask(config, false);
    }
    return factBuildService.rebuildMergeRequestFactsByTargets(task.sourceInstance(), scope.targets());
  }

  private FactBuildResponse rebuildIntegrationTestFacts(
      QueuedFactBuildTask task, GitlabSyncConfig config, String factType) {
    if (task.full()) {
      return integrationTestFactBuildService.rebuildFactsForConfig(config, true);
    }
    if (!taskService.hasSuccessfulFullBuild(task.sourceInstance(), factType)) {
      taskService.markQueuedTaskFullBuild(task.id());
      return integrationTestFactBuildService.rebuildFactsForConfig(config, true);
    }
    FactRefreshImpactScopeService.ImpactScope scope =
        impactScopeService.resolve(task.mirrorRunId(), task.sourceInstance(), factType);
    if (scope.fallbackRequired()) {
      taskService.markQueuedTaskFullBuild(task.id());
      return integrationTestFactBuildService.rebuildFactsForConfig(config, true);
    }
    return integrationTestFactBuildService.rebuildFactsByTargets(
        task.sourceInstance(), scope.targets());
  }

  private static String resolveWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName() + "-fact-" + UUID.randomUUID();
    } catch (Exception ignored) {
      return "fact-refresh-worker-" + UUID.randomUUID();
    }
  }
}
