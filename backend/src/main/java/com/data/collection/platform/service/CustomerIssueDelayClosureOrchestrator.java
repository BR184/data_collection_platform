package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.config.PlatformAsyncConfiguration;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import com.data.collection.platform.service.sync.SyncRunCompletionListenerOrder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 客户问题延期标签写回的编排。
 *
 * <p>本轮状态全部持久化在 {@code customer_issue_delay_closure_requests}：运行号、阶段、请求/处理/完成修订、
 * 入队游标、授权事实版本和租约。运行终态事件只负责唤醒检查，是否推进由权威进度、绑定镜像和来源资格决定；
 * 短周期恢复检查在进程重启后继续同一工作，不依赖内存中的在飞状态。
 *
 * <p>每次推进都是有界的：提交一次前置刷新后只等待，不在调度线程上阻塞，也不在每个 tick 重复提交刷新。
 * 领取、推进和释放都校验 owner、令牌与处理修订，旧处理者不能覆盖新处理者。
 */
@Service
@Slf4j
public class CustomerIssueDelayClosureOrchestrator {
  private static final int MAX_ADVANCES_PER_RUN = 20;
  private static final int MAX_ADVANCES_PER_SOURCE = 4;
  private static final int MAX_STAGE_STEPS = 4;
  private static final List<Integer> BACKOFF_MINUTES = List.of(1, 5, 15, 30, 60);

  private final GitlabConfigService configService;
  private final CustomerIssueDelayLabelWritebackService delayLabelWritebackService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private final CustomerIssueDelayClosureProgressRepository progressRepository;
  private final CustomerIssueDelayClosureRoundService roundService;
  private final CustomerIssueDelaySourceReadinessService readinessService;
  private final GitlabMirrorProperties properties;
  private final Executor platformAsyncExecutor;
  private final String owner = "customer-delay-closure-" + UUID.randomUUID();
  private final AtomicBoolean advancingAll = new AtomicBoolean(false);

  public CustomerIssueDelayClosureOrchestrator(
      GitlabConfigService configService,
      CustomerIssueDelayLabelWritebackService delayLabelWritebackService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService,
      CustomerIssueDelayClosureProgressRepository progressRepository,
      CustomerIssueDelayClosureRoundService roundService,
      CustomerIssueDelaySourceReadinessService readinessService,
      GitlabMirrorProperties properties,
      @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR) Executor platformAsyncExecutor) {
    this.configService = configService;
    this.delayLabelWritebackService = delayLabelWritebackService;
    this.preWritebackSyncService = preWritebackSyncService;
    this.progressRepository = progressRepository;
    this.roundService = roundService;
    this.readinessService = readinessService;
    this.properties = properties;
    this.platformAsyncExecutor = platformAsyncExecutor;
  }

  /** 周期触发：为每个启用来源登记请求，然后有界推进。 */
  public void runCycleForAllSources() {
    for (GitlabSyncConfig config : enabledConfigs()) {
      try {
        progressRepository.requestRound(config, "periodic");
      } catch (RuntimeException error) {
        log.warn(
            "Failed to request customer issue delay closure round, configId={}, sourceInstance={}",
            config.getId(),
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            error);
      }
    }
    advanceAll();
  }

  /** 短周期恢复检查：不登记新请求，只从持久进度继续被中断的等待或分页。 */
  public void checkProgressForAllSources() {
    advanceAll();
  }

  /**
   * 运行终态事件的唤醒方。
   *
   * <p>只按配置找到进度并唤醒；是否推进由 {@link CustomerIssueDelaySourceReadinessService} 重新核验
   * 绑定运行和事实收敛决定，因此无关运行先结束不会误推进本轮。
   *
   * @param event 已提交的运行终态事件
   */
  @Order(SyncRunCompletionListenerOrder.FACT_CONVERGENCE_CONSUMER)
  @EventListener
  public void onRunTerminal(SyncRunCompletionEvent event) {
    if (event == null || event.configId() == null) {
      return;
    }
    Optional<CustomerIssueDelayClosureProgress> progress =
        progressRepository.findByConfigId(event.configId());
    if (progress.isEmpty()) {
      return;
    }
    String sourceInstance = progress.get().sourceInstance();
    progressRepository.wake(sourceInstance);
    submitAdvance(sourceInstance);
  }

  private void advanceAll() {
    if (!advancingAll.compareAndSet(false, true)) {
      return;
    }
    try {
      for (int index = 0; index < MAX_ADVANCES_PER_RUN; index++) {
        Optional<CustomerIssueDelayClosureProgressClaim> claimed =
            progressRepository.claimNext(
                owner,
                UUID.randomUUID().toString(),
                properties.getCustomerIssueDelayProgressLeaseSeconds());
        if (claimed.isEmpty()) {
          return;
        }
        advanceAndRelease(claimed.get());
      }
    } finally {
      advancingAll.set(false);
    }
  }

  private void submitAdvance(String sourceInstance) {
    platformAsyncExecutor.execute(
        () -> {
          for (int index = 0; index < MAX_ADVANCES_PER_SOURCE; index++) {
            Optional<CustomerIssueDelayClosureProgressClaim> claimed =
                progressRepository.claimBySource(
                    sourceInstance,
                    owner,
                    UUID.randomUUID().toString(),
                    properties.getCustomerIssueDelayProgressLeaseSeconds());
            if (claimed.isEmpty()) {
              return;
            }
            advanceAndRelease(claimed.get());
          }
        });
  }

  private void advanceAndRelease(CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgressClaim current = claim;
    try {
      current = advance(claim);
    } catch (RuntimeException error) {
      log.warn(
          "Customer issue delay closure advance failed, sourceInstance={}, stage={}",
          claim.progress().sourceInstance(),
          claim.progress().stage(),
          error);
      current = recordFailure(claim, error.getMessage());
    } finally {
      progressRepository.release(current);
    }
  }

  private CustomerIssueDelayClosureProgressClaim advance(
      CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgressClaim current = claim;
    for (int step = 0; step < MAX_STAGE_STEPS; step++) {
      CustomerIssueDelayClosureProgress progress = current.progress();
      switch (progress.stage()) {
        case IDLE -> {
          if (!progress.hasPendingRequest()) {
            return current;
          }
          Optional<CustomerIssueDelayClosureProgress> requested =
              progressRepository.requestNewRound(current);
          if (requested.isEmpty()) {
            return current;
          }
          current = current.withProgress(requested.get());
        }
        case REQUESTED -> {
          return advanceRequested(current);
        }
        case WAIT_MIRROR -> {
          CustomerIssueDelayClosureStage before = progress.stage();
          Optional<CustomerIssueDelayClosureProgressClaim> next = advanceWaitMirror(current);
          if (next.isEmpty()) {
            return current;
          }
          current = next.get();
          if (current.progress().stage() == before) {
            return current;
          }
        }
        case WAIT_FACT -> {
          CustomerIssueDelayClosureStage before = progress.stage();
          Optional<CustomerIssueDelayClosureProgressClaim> next = advanceWaitFact(current);
          if (next.isEmpty()) {
            return current;
          }
          current = next.get();
          if (current.progress().stage() == before) {
            return current;
          }
        }
        case ENQUEUE -> {
          return advanceEnqueue(current);
        }
        default -> throw new IllegalStateException("未知的延期写回阶段：" + progress.stage());
      }
    }
    return current;
  }

  private CustomerIssueDelayClosureProgressClaim advanceRequested(
      CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgress progress = claim.progress();
    GitlabSyncConfig config = configFor(progress);
    if (config == null || !configService.isSourceEnabled(config)) {
      return revokeToIdle(claim, "数据源配置不存在或来源已停用");
    }
    if (!delayLabelWritebackService.isEnabled(config)) {
      CustomerIssueDelayClosureRoundService.StartResult result =
          roundService.refreshFactsOnly(claim, config, "延期标签写回开关关闭，仅重算延期事实");
      return result.claim();
    }
    CustomerIssueDelayClosureRoundService.StartResult result =
        roundService.startMirrorRound(claim, config);
    log.info(
        "Customer issue delay closure round started, sourceInstance={}, status={}, mirrorRunId={}, reason={}",
        progress.sourceInstance(),
        result.status(),
        result.claim() == null ? null : result.claim().progress().mirrorRunId(),
        result.reason());
    return result.claim() == null ? claim : result.claim();
  }

  private Optional<CustomerIssueDelayClosureProgressClaim> advanceWaitMirror(
      CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgress progress = claim.progress();
    GitlabSyncConfig config = configFor(progress);
    if (config == null || !configService.isSourceEnabled(config)) {
      return Optional.of(revokeToIdle(claim, "数据源配置不存在或来源已停用"));
    }
    CustomerIssueDelaySourceReadinessService.MirrorCheck mirror =
        readinessService.checkMirrorRun(
            config,
            progress.mirrorRunId(),
            preWritebackSyncService.preWritebackTables());
    if (mirror.complete()) {
      return progressRepository
          .moveToFactWait(claim, "前置镜像已完整成功，等待来源事实收敛")
          .map(claim::withProgress);
    }
    if (mirror.terminal()) {
      int failures = progress.failureCount() + 1;
      LocalDateTime next = LocalDateTime.now().plusMinutes(retryDelayMinutes(failures));
      log.warn(
          "Customer issue delay closure pre-writeback mirror did not complete, sourceInstance={}, mirrorRunId={}, reason={}",
          progress.sourceInstance(),
          progress.mirrorRunId(),
          mirror.reason());
      return progressRepository
          .restartRound(claim, mirror.reason(), failures, next)
          .map(claim::withProgress);
    }
    LocalDateTime next = LocalDateTime.now().plusNanos(checkDelayMillis() * 1_000_000L);
    return progressRepository
        .waitFor(claim, mirror.reason(), next)
        .map(claim::withProgress);
  }

  private Optional<CustomerIssueDelayClosureProgressClaim> advanceWaitFact(
      CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgress progress = claim.progress();
    GitlabSyncConfig config = configFor(progress);
    if (config == null || !configService.isSourceEnabled(config)) {
      return Optional.of(revokeToIdle(claim, "数据源配置不存在或来源已停用"));
    }
    if (!delayLabelWritebackService.isEnabled(config)) {
      return Optional.of(revokeToIdle(claim, "延期标签写回开关已关闭"));
    }
    CustomerIssueDelaySourceReadinessService.MirrorCheck mirror =
        readinessService.checkMirrorRun(
            config,
            progress.mirrorRunId(),
            preWritebackSyncService.preWritebackTables());
    if (mirror.terminal() && !mirror.complete()) {
      int failures = progress.failureCount() + 1;
      return progressRepository
          .restartRound(
              claim,
              mirror.reason(),
              failures,
              LocalDateTime.now().plusMinutes(retryDelayMinutes(failures)))
          .map(claim::withProgress);
    }
    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(
            config,
            progress.mirrorRunId(),
            preWritebackSyncService.preWritebackTables());
    if (!readiness.ready()) {
      return progressRepository
          .waitFor(
              claim,
              readiness.reason(),
              LocalDateTime.now().plusNanos(checkDelayMillis() * 1_000_000L))
          .map(claim::withProgress);
    }
    CustomerIssueDelayClosureRoundService.AuthorizationResult authorization =
        roundService.authorizeEnqueue(claim, config, readiness.factVersion());
    if (authorization.authorized()) {
      log.info(
          "Customer issue delay closure authorized, sourceInstance={}, mirrorRunId={}, factVersion={}",
          progress.sourceInstance(),
          progress.mirrorRunId(),
          authorization.factVersion());
      return Optional.of(authorization.claim());
    }
    log.info(
        "Customer issue delay closure delayed before authorization, sourceInstance={}, reason={}",
        progress.sourceInstance(),
        authorization.reason());
    return progressRepository
        .waitFor(
            claim,
            authorization.reason(),
            LocalDateTime.now().plusNanos(checkDelayMillis() * 1_000_000L))
        .map(claim::withProgress);
  }

  private CustomerIssueDelayClosureProgressClaim advanceEnqueue(
      CustomerIssueDelayClosureProgressClaim claim) {
    CustomerIssueDelayClosureProgress progress = claim.progress();
    if (progress.configId() == null || progress.authorizedFactVersion() == null) {
      return revokeToFactWait(claim, "本轮写回授权不完整");
    }
    CustomerIssueDelayClosureRoundService.EnqueueResult result =
        roundService.enqueuePage(
            claim,
            progress.configId(),
            progress.authorizedFactVersion(),
            Math.max(1, properties.getCustomerIssueDelayEnqueueBatchSize()));
    return switch (result.status()) {
      case ADVANCED, COMPLETED -> {
        log.info(
            "Customer issue delay closure enqueued a page, sourceInstance={}, enqueued={}, status={}, cursor={}",
            progress.sourceInstance(),
            result.enqueued(),
            result.status(),
            result.claim() == null ? null : result.claim().progress().enqueueCursorIssueId());
        yield result.claim() == null ? claim : result.claim();
      }
      case CHANGED -> revokeToFactWait(claim, result.reason());
      case REVOKED -> revokeToFactWait(claim, result.reason());
    };
  }

  private CustomerIssueDelayClosureProgressClaim revokeToFactWait(
      CustomerIssueDelayClosureProgressClaim claim, String reason) {
    GitlabSyncConfig config = configFor(claim.progress());
    if (config == null
        || !configService.isSourceEnabled(config)
        || !delayLabelWritebackService.isEnabled(config)) {
      return revokeToIdle(claim, reason);
    }
    return progressRepository
        .revokeToFactWait(
            claim,
            reason,
            claim.progress().failureCount(),
            LocalDateTime.now().plusNanos(checkDelayMillis() * 1_000_000L))
        .map(claim::withProgress)
        .orElse(claim);
  }

  private CustomerIssueDelayClosureProgressClaim revokeToIdle(
      CustomerIssueDelayClosureProgressClaim claim, String reason) {
    return progressRepository
        .revokeToIdle(claim, reason)
        .map(claim::withProgress)
        .orElse(claim);
  }

  private CustomerIssueDelayClosureProgressClaim recordFailure(
      CustomerIssueDelayClosureProgressClaim claim, String message) {
    int failures = claim.progress().failureCount() + 1;
    return progressRepository
        .recordFailure(
            claim,
            message == null ? "延期写回推进失败" : message,
            failures,
            LocalDateTime.now().plusMinutes(retryDelayMinutes(failures)))
        .map(claim::withProgress)
        .orElse(claim);
  }

  private GitlabSyncConfig configFor(CustomerIssueDelayClosureProgress progress) {
    if (progress.configId() == null) {
      return null;
    }
    GitlabSyncConfig config = configService.getConfigById(progress.configId());
    if (config == null) {
      return null;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return sourceInstance.equals(progress.sourceInstance()) ? config : null;
  }

  private List<GitlabSyncConfig> enabledConfigs() {
    return configService.listConfigs().stream()
        .filter(config -> config.getId() != null)
        .filter(configService::isSourceEnabled)
        .toList();
  }

  private int checkDelayMillis() {
    return Math.max(1_000, properties.getCustomerIssueDelayProgressCheckDelayMs());
  }

  private int retryDelayMinutes(int failures) {
    return BACKOFF_MINUTES.get(Math.min(Math.max(0, failures - 1), BACKOFF_MINUTES.size() - 1));
  }
}
