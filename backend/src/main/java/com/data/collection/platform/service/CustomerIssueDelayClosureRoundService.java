package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 延期写回轮次的事务边界。
 *
 * <p>三类写入必须原子：提交前置镜像与绑定进度、延期重算与阶段授权、一页候选登记与游标推进。
 * 事务失败时不得留下"已授权但未重算"或"已登记但游标未推进"的中间状态。
 */
@Service
public class CustomerIssueDelayClosureRoundService {
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private final CustomerIssueDelayLabelWritebackService writebackService;
  private final CustomerIssueDelayLabelWritebackQueueService queueService;
  private final CustomerIssueDelaySourceReadinessService readinessService;
  private final CustomerIssueDelayClosureProgressRepository progressRepository;

  public CustomerIssueDelayClosureRoundService(
      GitlabConfigService configService,
      FactBuildService factBuildService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService,
      CustomerIssueDelayLabelWritebackService writebackService,
      CustomerIssueDelayLabelWritebackQueueService queueService,
      CustomerIssueDelaySourceReadinessService readinessService,
      CustomerIssueDelayClosureProgressRepository progressRepository) {
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.preWritebackSyncService = preWritebackSyncService;
    this.writebackService = writebackService;
    this.queueService = queueService;
    this.readinessService = readinessService;
    this.progressRepository = progressRepository;
  }

  /**
   * 撤销上一轮授权并提交本轮前置镜像，成功后在同一事务内绑定真实运行号。
   *
   * @param claim 当前进度领取身份
   * @param config 当前数据源配置
   * @return 本轮启动结果；提交被拒绝时只重算延期事实，不取得写回授权
   */
  @Transactional
  public StartResult startMirrorRound(
      CustomerIssueDelayClosureProgressClaim claim, GitlabSyncConfig config) {
    CustomerIssueDelayClosureProgressClaim current =
        progressRepository
            .beginNewRound(claim)
            .map(claim::withProgress)
            .orElseThrow(
                () -> new IllegalStateException("延期写回进度所有权已丢失，取消本轮前置刷新"));
    CustomerIssueDelayPreWritebackSyncService.Outcome outcome =
        preWritebackSyncService.submitPreWritebackSync(config);
    return switch (outcome) {
      case CustomerIssueDelayPreWritebackSyncService.Outcome.Submitted submitted -> {
        CustomerIssueDelayClosureProgressClaim bound =
            progressRepository
                .bindMirror(current, submitted.runId())
                .map(current::withProgress)
                .orElseThrow(
                    () -> new IllegalStateException("延期写回进度所有权已丢失，回滚本轮前置刷新"));
        yield new StartResult(StartStatus.WAITING, bound, null);
      }
      case CustomerIssueDelayPreWritebackSyncService.Outcome.Rejected rejected -> {
        factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
        CustomerIssueDelayClosureProgressClaim finished =
            progressRepository
                .finishFactOnly(current, rejected.reason())
                .map(current::withProgress)
                .orElseThrow(
                    () -> new IllegalStateException("延期写回进度所有权已丢失，回滚延期事实重算"));
        yield new StartResult(StartStatus.FACT_ONLY, finished, rejected.reason());
      }
    };
  }

  /**
   * 写回关闭时只重算延期事实，不取得标签写入授权。
   */
  @Transactional
  public StartResult refreshFactsOnly(
      CustomerIssueDelayClosureProgressClaim claim, GitlabSyncConfig config, String reason) {
    CustomerIssueDelayClosureProgressClaim current =
        progressRepository
            .beginNewRound(claim)
            .map(claim::withProgress)
            .orElseThrow(() -> new IllegalStateException("延期写回进度所有权已丢失，取消延期事实重算"));
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
    CustomerIssueDelayClosureProgressClaim finished =
        progressRepository
            .finishFactOnly(current, reason)
            .map(current::withProgress)
            .orElseThrow(() -> new IllegalStateException("延期写回进度所有权已丢失，回滚延期事实重算"));
    return new StartResult(StartStatus.FACT_ONLY, finished, reason);
  }

  /**
   * 在事实收敛后重算延期三列并授予本轮入队授权。
   *
   * <p>授权版本取重算提交前读取的来源事实上界；重算与授权在同一事务内提交，异常时两者一起回滚。
   *
   * @param claim 当前进度领取身份
   * @param config 当前数据源配置
   * @param expectedFactVersion 资格评估时读到的事实版本
   * @return 授权结果
   */
  @Transactional
  public AuthorizationResult authorizeEnqueue(
      CustomerIssueDelayClosureProgressClaim claim,
      GitlabSyncConfig config,
      long expectedFactVersion) {
    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, claim.progress().mirrorRunId(), preWritebackSyncService.preWritebackTables());
    if (!readiness.ready() || readiness.factVersion() != expectedFactVersion) {
      return AuthorizationResult.rejected(readiness.reason());
    }
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    long factVersion = readinessService.currentFactVersion(sourceInstance);
    if (factVersion != expectedFactVersion) {
      return AuthorizationResult.rejected(
          "重算期间相关事实版本发生变化：" + expectedFactVersion + " -> " + factVersion);
    }
    Optional<CustomerIssueDelayClosureProgress> authorized =
        progressRepository.authorizeEnqueue(claim, expectedFactVersion);
    return authorized
        .map(progress -> AuthorizationResult.authorized(claim.withProgress(progress), factVersion))
        .orElseGet(() -> AuthorizationResult.rejected("本轮进度所有权已丢失"));
  }

  /**
   * 在一个事务内完成"一致读取当前事实与标签、登记候选、推进游标"。
   *
   * <p>授权版本变化、来源不再收敛或配置被关闭时不写入任何候选，由调用方撤销授权并重新收敛。
   *
   * @param claim 当前进度领取身份
   * @param configId 数据源配置编号
   * @param expectedFactVersion 本轮授权事实版本
   * @param batchSize 本页最大行数
   * @return 本页结果
   */
  @Transactional
  public EnqueueResult enqueuePage(
      CustomerIssueDelayClosureProgressClaim claim,
      Long configId,
      long expectedFactVersion,
      int batchSize) {
    GitlabSyncConfig config = configService.getConfigById(configId);
    if (config == null || !configService.isSourceEnabled(config)) {
      return EnqueueResult.revoked("数据源配置不存在或来源已停用");
    }
    if (!writebackService.isEnabled(config)) {
      return EnqueueResult.revoked("延期标签写回开关已关闭");
    }
    CustomerIssueDelayClosureProgress progress = claim.progress();
    if (!progress.authorized()) {
      return EnqueueResult.revoked("本轮写回授权已撤销");
    }
    CustomerIssueDelaySourceReadinessService.Readiness readiness =
        readinessService.assess(config, progress.mirrorRunId(), preWritebackSyncService.preWritebackTables());
    if (!readiness.ready()) {
      return EnqueueResult.revoked(readiness.reason());
    }
    if (readiness.factVersion() != expectedFactVersion) {
      return EnqueueResult.changed(
          "相关事实版本已从 " + expectedFactVersion + " 变为 " + readiness.factVersion());
    }
    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(
            config,
            progress.authorizationRevision(),
            expectedFactVersion,
            progress.mirrorRunId(),
            progress.enqueueCursorIssueId(),
            batchSize);
    CustomerIssueDelayClosureProgress updated =
        (page.hasMore()
                ? progressRepository.advanceCursor(claim, page.lastIssueId())
                : progressRepository.completeRound(claim))
            .orElseThrow(
                () -> new IllegalStateException("延期写回进度所有权已丢失，回滚本页候选登记"));
    CustomerIssueDelayClosureProgressClaim next = claim.withProgress(updated);
    return page.hasMore()
        ? EnqueueResult.advanced(next, page.enqueued())
        : EnqueueResult.completed(next, page.enqueued());
  }

  /** 本轮启动结果。 */
  public record StartResult(
      StartStatus status, CustomerIssueDelayClosureProgressClaim claim, String reason) {}

  /** 本轮启动状态。 */
  public enum StartStatus {
    /** 已绑定前置镜像，等待其完整成功。 */
    WAITING,
    /** 仅完成延期事实重算，不写标签。 */
    FACT_ONLY
  }

  /** 授权结果。 */
  public record AuthorizationResult(
      boolean authorized,
      CustomerIssueDelayClosureProgressClaim claim,
      String reason,
      long factVersion) {
    static AuthorizationResult authorized(
        CustomerIssueDelayClosureProgressClaim claim, long factVersion) {
      return new AuthorizationResult(true, claim, null, factVersion);
    }

    static AuthorizationResult rejected(String reason) {
      return new AuthorizationResult(false, null, reason, 0L);
    }
  }

  /** 一页入队结果。 */
  public record EnqueueResult(
      EnqueueStatus status,
      CustomerIssueDelayClosureProgressClaim claim,
      int enqueued,
      String reason) {
    static EnqueueResult advanced(CustomerIssueDelayClosureProgressClaim claim, int enqueued) {
      return new EnqueueResult(EnqueueStatus.ADVANCED, claim, enqueued, null);
    }

    static EnqueueResult completed(CustomerIssueDelayClosureProgressClaim claim, int enqueued) {
      return new EnqueueResult(EnqueueStatus.COMPLETED, claim, enqueued, null);
    }

    static EnqueueResult changed(String reason) {
      return new EnqueueResult(EnqueueStatus.CHANGED, null, 0, reason);
    }

    static EnqueueResult revoked(String reason) {
      return new EnqueueResult(EnqueueStatus.REVOKED, null, 0, reason);
    }
  }

  /** 入队状态。 */
  public enum EnqueueStatus {
    ADVANCED,
    COMPLETED,
    CHANGED,
    REVOKED
  }
}
