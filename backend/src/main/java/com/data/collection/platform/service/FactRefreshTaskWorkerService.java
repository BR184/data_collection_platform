package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
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

  public FactRefreshTaskWorkerService(
      FactBuildTaskService taskService,
      GitlabConfigService configService,
      FactBuildService factBuildService,
      IntegrationTestFactBuildService integrationTestFactBuildService,
      GitlabMirrorProperties properties) {
    this.taskService = taskService;
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.properties = properties;
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

  void execute(QueuedFactBuildTask task) {
    try {
      GitlabSyncConfig config = configService.getConfigById(task.configId());
      FactBuildResponse response = switch (normalizeFactType(task.factType())) {
        case "ISSUE" -> factBuildService.rebuildIssueFactsForQueuedTask(config, task.full());
        case "MERGE_REQUEST" -> factBuildService.rebuildMergeRequestFactsForQueuedTask(config, task.full());
        case "INTEGRATION_TEST" -> integrationTestFactBuildService.rebuildFactsForConfig(config, task.full());
        default -> throw new IllegalArgumentException("Unsupported fact refresh type: " + task.factType());
      };
      taskService.finishQueuedTask(task.id(), "SUCCESS", response.affectedRows(), response.message(), null);
    } catch (Exception e) {
      taskService.finishQueuedTask(task.id(), "FAILED", 0, "Fact refresh failed", e.getMessage());
      log.warn("Fact refresh task failed, taskId={}, factType={}", task.id(), task.factType(), e);
    }
  }

  private String normalizeFactType(String factType) {
    return factType == null ? "" : factType.trim().toUpperCase(Locale.ROOT);
  }

  private static String resolveWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName() + "-fact-" + UUID.randomUUID();
    } catch (Exception ignored) {
      return "fact-refresh-worker-" + UUID.randomUUID();
    }
  }
}
