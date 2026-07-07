package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackWorkerService {
  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final CustomerIssueDelayLabelWritebackService writebackService;
  private final CustomerIssueDelayLabelWritebackPlanner planner;
  private final CustomerIssueDelayLabelWritebackQueueService queueService;
  private final String workerOwner = "customer-delay-label-worker-" + UUID.randomUUID();
  private final AtomicBoolean running = new AtomicBoolean(false);

  public CustomerIssueDelayLabelWritebackWorkerService(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      CustomerIssueDelayLabelWritebackService writebackService,
      CustomerIssueDelayLabelWritebackPlanner planner,
      CustomerIssueDelayLabelWritebackQueueService queueService) {
    this.properties = properties;
    this.configService = configService;
    this.writebackService = writebackService;
    this.planner = planner;
    this.queueService = queueService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.customer-issue-delay-writeback-worker-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled() || !properties.isCustomerIssueDelayWritebackWorkerEnabled()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    try {
      CustomerIssueDelayLabelWritebackJob job =
          queueService.claimNext(workerOwner, properties.getCustomerIssueDelayWritebackLeaseSeconds());
      if (job != null) {
        process(job);
      }
    } finally {
      running.set(false);
    }
  }

  void process(CustomerIssueDelayLabelWritebackJob job) {
    if (job == null || job.id() == null) {
      return;
    }
    GitlabSyncConfig config = configFor(job.sourceInstance());
    if (!writebackService.isEnabled(config)) {
      queueService.markSkipped(job.id(), "延期标签写回开关关闭，任务未调用 GitLab");
      return;
    }
    Optional<IssueFact> candidate = queueService.loadCandidate(job.sourceInstance(), job.projectId(), job.issueIid());
    if (candidate.isEmpty()) {
      queueService.markSkipped(job.id(), "议题已不在延期标签写回范围内");
      return;
    }
    CustomerIssueDelayLabelWritebackPlan plan = planner.plan(candidate.get());
    if (!plan.hasChanges()) {
      queueService.markSkipped(job.id(), "GitLab 标签已与延期事实一致");
      return;
    }
    try {
      int status = writebackService.sendLabelUpdate(config, job.projectId(), job.issueIid(), plan.change());
      queueService.markSucceeded(job.id());
      log.info(
          "Customer issue delay label writeback succeeded, sourceInstance={}, projectId={}, issueIid={}, httpStatus={}, addLabels={}, removeLabels={}",
          job.sourceInstance(),
          job.projectId(),
          job.issueIid(),
          status,
          plan.change().addLabels(),
          plan.change().removeLabels());
    } catch (CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error) {
      handleHttpFailure(job, error);
    } catch (IOException error) {
      queueService.markRetry(job.id(), job.attemptCount(), job.maxAttempts(), error.getMessage(), null);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      queueService.markRetry(job.id(), job.attemptCount(), job.maxAttempts(), error.getMessage(), null);
    }
  }

  private void handleHttpFailure(
      CustomerIssueDelayLabelWritebackJob job,
      CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error) {
    int status = error.httpStatus();
    if (isRetryable(status)) {
      queueService.markRetry(job.id(), job.attemptCount(), job.maxAttempts(), error.getMessage(), status);
      return;
    }
    queueService.markDead(job.id(), error.getMessage(), status);
  }

  private boolean isRetryable(int httpStatus) {
    return httpStatus == 429 || httpStatus >= 500;
  }

  private GitlabSyncConfig configFor(String sourceInstance) {
    String normalized = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    List<GitlabSyncConfig> configs = configService.listConfigs();
    return configs.stream()
        .filter(config -> normalized.equals(GitlabSourceInstanceSupport.sourceInstanceOf(config)))
        .findFirst()
        .orElseGet(configService::getConfig);
  }
}
