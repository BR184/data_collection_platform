package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 延期标签写回 worker。
 *
 * <p>发送前依次复核：配置唯一且仍允许写回、本轮前置镜像成功、来源仍收敛、相关事实版本与本轮授权一致、
 * 当前议题仍在范围内、作业租约仍有效。等待期间来源变化只保留待评估工作并让编排重新收敛，
 * 不计成 HTTP 失败，也不会为每条任务重复提交前置镜像。
 *
 * <p>结算必须匹配领取令牌、领取修订和任务修订；处理中到达的新差异会推进任务修订，旧执行结果无法
 * 清除后续工作。网络请求发出后无法与数据库撤销组成原子事务，超时按"发送结果未知"重试，
 * 由下一次重读当前标签自然收敛，不承诺绝对只发送一次。
 */
@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackWorkerService {
  private static final int DEFER_SECONDS = 30;

  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final CustomerIssueDelayLabelWritebackService writebackService;
  private final CustomerIssueDelayLabelWritebackPlanner planner;
  private final CustomerIssueDelayLabelWritebackQueueService queueService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private final CustomerIssueDelayClosureProgressRepository progressRepository;
  private final CustomerIssueDelaySourceReadinessService readinessService;
  private final String workerOwner = "customer-delay-label-worker-" + UUID.randomUUID();
  private final AtomicBoolean running = new AtomicBoolean(false);

  public CustomerIssueDelayLabelWritebackWorkerService(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      CustomerIssueDelayLabelWritebackService writebackService,
      CustomerIssueDelayLabelWritebackPlanner planner,
      CustomerIssueDelayLabelWritebackQueueService queueService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService,
      CustomerIssueDelayClosureProgressRepository progressRepository,
      CustomerIssueDelaySourceReadinessService readinessService) {
    this.properties = properties;
    this.configService = configService;
    this.writebackService = writebackService;
    this.planner = planner;
    this.queueService = queueService;
    this.preWritebackSyncService = preWritebackSyncService;
    this.progressRepository = progressRepository;
    this.readinessService = readinessService;
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

  /**
   * 执行一个已领取作业。
   *
   * @param job 已带领取令牌与修订的作业
   */
  void process(CustomerIssueDelayLabelWritebackJob job) {
    if (job == null || job.id() == null) {
      return;
    }
    GitlabSyncConfig config = resolveConfig(job);
    if (config == null) {
      queueService.markSkipped(job, "数据源配置缺失或不唯一，未调用 GitLab");
      return;
    }
    if (!configService.isSourceEnabled(config)) {
      queueService.markSkipped(job, "数据源已停用，任务未调用 GitLab");
      return;
    }
    if (!writebackService.isEnabled(config)) {
      queueService.markSkipped(job, "延期标签写回开关关闭，任务未调用 GitLab");
      return;
    }
    AuthorizationCheck authorization = authorize(job, config);
    if (!authorization.allowed()) {
      if (authorization.obsolete()) {
        queueService.markSkipped(job, authorization.reason());
      } else {
        defer(job, authorization.reason());
      }
      return;
    }
    Optional<IssueFact> candidate;
    try {
      candidate =
          queueService.loadCandidate(job.sourceInstance(), job.projectId(), job.issueIid());
    } catch (DataAccessException error) {
      defer(job, "读取当前事实或标签失败，未调用 GitLab：" + error.getMessage());
      return;
    }
    if (candidate.isEmpty()) {
      queueService.markSkipped(job, "议题已不在延期标签写回范围内");
      return;
    }
    CustomerIssueDelayLabelWritebackPlan plan = planner.plan(candidate.get());
    if (!plan.hasChanges()) {
      queueService.markSkipped(job, "GitLab 标签已与延期事实一致");
      return;
    }
    AuthorizationCheck finalCheck = authorize(job, config);
    if (!finalCheck.allowed()) {
      if (finalCheck.obsolete()) {
        queueService.markSkipped(job, finalCheck.reason());
      } else {
        defer(job, finalCheck.reason());
      }
      return;
    }
    if (!queueService.renewLease(job, properties.getCustomerIssueDelayWritebackLeaseSeconds())) {
      log.info(
          "Customer issue delay label writeback ownership lost before send, projectId={}, issueIid={}",
          job.projectId(),
          job.issueIid());
      return;
    }
    try {
      int status = writebackService.sendLabelUpdate(config, job.projectId(), job.issueIid(), plan.change());
      boolean settled = queueService.markSucceeded(job);
      if (!settled) {
        log.info(
            "Customer issue delay label writeback succeeded but a newer revision arrived, keep it pending,"
                + " projectId={}, issueIid={}, addLabels={}, removeLabels={}",
            job.projectId(),
            job.issueIid(),
            plan.change().addLabels(),
            plan.change().removeLabels());
        return;
      }
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
      queueService.markRetry(job, "发送结果未知：" + error.getMessage(), null);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      queueService.markRetry(job, "发送结果未知：" + error.getMessage(), null);
    }
  }

  private AuthorizationCheck authorize(
      CustomerIssueDelayLabelWritebackJob job, GitlabSyncConfig config) {
    Optional<CustomerIssueDelayClosureProgress> progress =
        progressRepository.findBySource(job.sourceInstance());
    if (progress.isEmpty()) {
      return AuthorizationCheck.waitingFor("来源写回进度不存在，等待新一轮收敛");
    }
    CustomerIssueDelayClosureProgress current = progress.get();
    if (current.configId() == null || !current.configId().equals(job.configId())) {
      return AuthorizationCheck.waitingFor("数据源配置已变化，等待新一轮授权");
    }
    if (job.authorizationRevision() == null) {
      return AuthorizationCheck.waitingFor("作业缺少本轮授权修订，等待重新授权");
    }
    if (current.authorizationRevision() == null) {
      return AuthorizationCheck.waitingFor("本轮授权已撤销，等待新一轮前置周期");
    }
    if (job.authorizationRevision() < current.authorizationRevision()) {
      return AuthorizationCheck.obsoleteReason("该作业属于较早的写回轮次，已由新一轮候选重新评估");
    }
    if (job.requiredFactVersion() == null) {
      return AuthorizationCheck.waitingFor("作业缺少授权事实版本，等待重新授权");
    }
    if (current.authorizedFactVersion() == null) {
      return AuthorizationCheck.waitingFor("本轮授权事实版本已撤销，等待新一轮授权");
    }
    if (job.requiredFactVersion() < current.authorizedFactVersion()) {
      return AuthorizationCheck.obsoleteReason("该作业的授权事实版本已过期，已由新一轮候选重新评估");
    }
    if (!job.authorizationRevision().equals(current.authorizationRevision())
        || !job.requiredFactVersion().equals(current.authorizedFactVersion())) {
      return AuthorizationCheck.waitingFor("作业授权与当前轮次不一致，等待重新授权");
    }
    CustomerIssueDelaySourceReadinessService.AuthorizationCheck readiness =
        readinessService.validateRoundAuthorization(
            config, current, preWritebackSyncService.preWritebackTables());
    if (!readiness.allowed()) {
      return AuthorizationCheck.waitingFor(readiness.reason());
    }
    return AuthorizationCheck.granted();
  }

  private void handleHttpFailure(
      CustomerIssueDelayLabelWritebackJob job,
      CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error) {
    int status = error.httpStatus();
    if (isRetryable(status)) {
      queueService.markRetry(job, error.getMessage(), status);
      return;
    }
    queueService.markDead(job, error.getMessage(), status);
  }

  private boolean isRetryable(int httpStatus) {
    return httpStatus == 429 || httpStatus >= 500;
  }

  private void defer(CustomerIssueDelayLabelWritebackJob job, String reason) {
    queueService.defer(job, reason, LocalDateTime.now().plusSeconds(DEFER_SECONDS));
  }

  /**
   * 按作业携带的真实 config_id 解析配置，并要求该来源实例只有唯一配置。
   *
   * <p>找不到、配置身份不符或同一来源存在多个配置时不发送 HTTP，也不回退到默认配置。
   */
  private GitlabSyncConfig resolveConfig(CustomerIssueDelayLabelWritebackJob job) {
    if (job.configId() == null) {
      return null;
    }
    String sourceInstance =
        GitlabSourceInstanceSupport.normalizeSourceInstance(job.sourceInstance());
    List<GitlabSyncConfig> configs =
        configService.listConfigs().stream()
            .filter(
                config ->
                    sourceInstance.equals(
                        GitlabSourceInstanceSupport.sourceInstanceOf(config)))
            .toList();
    if (configs.size() != 1) {
      log.warn(
          "Customer issue delay label writeback skipped because source config is missing or ambiguous,"
              + " sourceInstance={}, configCount={}",
          sourceInstance,
          configs.size());
      return null;
    }
    GitlabSyncConfig config = configs.getFirst();
    if (!job.configId().equals(config.getId())) {
      log.warn(
          "Customer issue delay label writeback skipped because config binding changed, jobConfigId={}, currentConfigId={}",
          job.configId(),
          config.getId());
      return null;
    }
    return config;
  }

  private record AuthorizationCheck(boolean allowed, boolean obsolete, String reason) {
    static AuthorizationCheck granted() {
      return new AuthorizationCheck(true, false, null);
    }

    static AuthorizationCheck waitingFor(String reason) {
      return new AuthorizationCheck(false, false, reason);
    }

    static AuthorizationCheck obsoleteReason(String reason) {
      return new AuthorizationCheck(false, true, reason);
    }
  }
}
