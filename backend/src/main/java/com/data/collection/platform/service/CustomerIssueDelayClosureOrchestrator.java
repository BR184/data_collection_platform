package com.data.collection.platform.service;

import com.data.collection.platform.config.PlatformAsyncConfiguration;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import com.data.collection.platform.service.sync.SyncRunCompletionListenerOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 客户问题延期标签写回的编排：由运行终态事件推进的三段流程。
 *
 * <ol>
 *   <li>阶段 1：提交 {@code TABLE_REFRESH}（issues/notes/label_links/labels）刷新镜像；
 *   <li>阶段 2：镜像终态后由来源级事实消费者自动发布增量，本类不介入，只等收敛；
 *   <li>阶段 3：收到运行终态事件、且该来源实例已无未发布 ISSUE 事实目标时，重算延期事实并登记写回候选。
 * </ol>
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li><b>不轮询、不设超时</b>：等待由运行终态事件驱动，因此镜像耗时超过任何固定阈值也不会整轮跳过；
 *       收敛判据是 {@link SyncFactPublicationStateService#countUnpublishedTargets} 的版本栅栏，
 *       与"来源级、无父运行"的事实消费者语义一致。
 *   <li><b>不占用调度线程</b>：阶段 3 一律提交到应用异步执行器，调用方（调度触发与终态事件监听）
 *       都只做常量时间的工作。
 *   <li><b>按数据源单飞 + 周期重启</b>：在飞状态以数据源配置为粒度（阶段 1 提交与阶段 3 副作用都是
 *       逐配置的），因此同一配置同一时刻至多一个编排在飞、不同配置互不影响；编排只拥有一个调度周期，
 *       跨周期仍在飞则重启（重启不会产生并行镜像——同互斥域的活跃运行会被复用——也不会重复写回，
 *       候选登记是幂等 upsert）。
 *   <li><b>登记先于评估</b>：终态事件监听顺序由 {@link SyncRunCompletionListenerOrder} 约束，
 *       保证读取收敛判据时事实发布登记已提交。
 * </ul>
 */
@Service
@Slf4j
public class CustomerIssueDelayClosureOrchestrator {
  /**
   * 延期标签写回依赖 {@code issue_fact} 的延期事实，因此只等待 ISSUE 事实族的发布收敛；
   * 变更合并请求事实族与本次写回判定无关。
   */
  private static final FactType DELAY_FACT_TYPE = FactType.ISSUE;

  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final CustomerIssueDelayLabelWritebackService delayLabelWritebackService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private final CustomerIssueDelayLabelWritebackQueueService queueService;
  private final SyncFactPublicationStateService publicationStateService;
  private final Executor platformAsyncExecutor;
  /** 在飞编排，按数据源配置编号索引：阶段 1 与阶段 3 都是逐配置的副作用。 */
  private final Map<Long, InFlightCycle> inFlightCycles = new ConcurrentHashMap<>();
  private final AtomicInteger schedulingPeriod = new AtomicInteger();

  public CustomerIssueDelayClosureOrchestrator(
      GitlabConfigService configService,
      FactBuildService factBuildService,
      CustomerIssueDelayLabelWritebackService delayLabelWritebackService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService,
      CustomerIssueDelayLabelWritebackQueueService queueService,
      SyncFactPublicationStateService publicationStateService,
      @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR) Executor platformAsyncExecutor) {
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.delayLabelWritebackService = delayLabelWritebackService;
    this.preWritebackSyncService = preWritebackSyncService;
    this.queueService = queueService;
    this.publicationStateService = publicationStateService;
    this.platformAsyncExecutor = platformAsyncExecutor;
  }

  /**
   * 推进一个调度周期：为每个已启用数据源发起或重启编排。
   *
   * <p>本方法只在应用异步执行器上运行，绝不阻塞调度线程。
   */
  public void runCycleForAllSources() {
    int period = schedulingPeriod.incrementAndGet();
    for (GitlabSyncConfig config : enabledConfigs()) {
      try {
        startCycle(config, period);
      } catch (RuntimeException error) {
        log.warn(
            "Customer issue delay closure cycle failed to start, sourceInstance={}",
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            error);
      }
    }
  }

  /**
   * 运行终态事件的收敛评估方。
   *
   * <p>只在"本来源实例存在在飞编排"时做事，因此自动增量同步等无关运行不会触发写回。
   *
   * @param event 已提交的运行终态事件
   */
  @Order(SyncRunCompletionListenerOrder.FACT_CONVERGENCE_CONSUMER)
  @EventListener
  public void onRunTerminal(SyncRunCompletionEvent event) {
    if (event == null || event.configId() == null) {
      return;
    }
    InFlightCycle cycle = inFlightCycles.get(event.configId());
    if (cycle == null) {
      return;
    }
    if (isStageOneFailure(event, cycle)) {
      if (inFlightCycles.remove(event.configId(), cycle)) {
        log.warn(
            "Customer issue delay closure cycle aborted because the pre-writeback sync run did not succeed,"
                + " configId={}, sourceInstance={}, mirrorRunId={}, status={}",
            event.configId(),
            GitlabSourceInstanceSupport.sourceInstanceOf(cycle.config()),
            cycle.mirrorRunId(),
            event.status());
      }
      return;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(cycle.config());
    long unpublishedTargets =
        publicationStateService.countUnpublishedTargets(sourceInstance, DELAY_FACT_TYPE);
    if (unpublishedTargets > 0L) {
      log.info(
          "Customer issue delay closure is waiting for fact publication, configId={}, sourceInstance={},"
              + " factType={}, unpublishedTargets={}, terminalRunId={}, terminalRunType={},"
              + " terminalRunStatus={}",
          event.configId(),
          sourceInstance,
          DELAY_FACT_TYPE,
          unpublishedTargets,
          event.runId(),
          event.runType(),
          event.status());
      return;
    }
    if (!inFlightCycles.remove(event.configId(), cycle)) {
      return;
    }
    log.info(
        "Customer issue delay closure publication converged, configId={}, sourceInstance={},"
            + " mirrorRunId={}, factType={}, terminalRunId={}, terminalRunType={}, terminalRunStatus={}",
        event.configId(),
        sourceInstance,
        cycle.mirrorRunId(),
        DELAY_FACT_TYPE,
        event.runId(),
        event.runType(),
        event.status());
    submitDelayClosure(cycle);
  }

  private void startCycle(GitlabSyncConfig config, int period) {
    Long configId = config.getId();
    boolean writebackEnabled = delayLabelWritebackService.isEnabled(config);
    if (!writebackEnabled) {
      // 写回关闭时只刷新延期事实：没有标签写回，就不需要镜像前置刷新与发布收敛等待。
      inFlightCycles.remove(configId);
      submitDelayClosure(new InFlightCycle(config, null, period, false));
      return;
    }
    InFlightCycle existing = inFlightCycles.get(configId);
    if (existing != null && existing.period() >= period) {
      log.info(
          "Customer issue delay closure cycle is already in flight, configId={}, sourceInstance={},"
              + " mirrorRunId={}",
          configId,
          GitlabSourceInstanceSupport.sourceInstanceOf(config),
          existing.mirrorRunId());
      return;
    }
    if (existing != null) {
      log.warn(
          "Customer issue delay closure cycle outlived one scheduling period and is restarted,"
              + " configId={}, sourceInstance={}, mirrorRunId={}, startedInPeriod={}, currentPeriod={}",
          configId,
          GitlabSourceInstanceSupport.sourceInstanceOf(config),
          existing.mirrorRunId(),
          existing.period(),
          period);
    }
    switch (preWritebackSyncService.submitPreWritebackSync(config)) {
      case CustomerIssueDelayPreWritebackSyncService.Outcome.NotRequired ignored -> {
        inFlightCycles.remove(configId);
        submitDelayClosure(new InFlightCycle(config, null, period, true));
      }
      case CustomerIssueDelayPreWritebackSyncService.Outcome.Submitted submitted ->
          inFlightCycles.put(
              configId, new InFlightCycle(config, submitted.runId(), period, true));
      case CustomerIssueDelayPreWritebackSyncService.Outcome.Rejected rejected ->
          log.warn(
              "Customer issue delay closure cycle skipped because the pre-writeback sync run was rejected,"
                  + " configId={}, sourceInstance={}, reason={}",
              configId,
              GitlabSourceInstanceSupport.sourceInstanceOf(config),
              rejected.reason());
    }
  }

  /**
   * 判断终态事件是否属于本次编排的阶段 1 运行且未成功。
   *
   * <p>阶段 1 只承认 {@code SUCCESS}：部分成功的镜像不足以作为写回依据，与其基于残缺镜像发出标签，
   * 不如放弃本轮并由下一个调度周期重新发起。
   */
  private boolean isStageOneFailure(SyncRunCompletionEvent event, InFlightCycle cycle) {
    return event.runType() == SyncRunType.TABLE_REFRESH
        && cycle.mirrorRunId() != null
        && event.runId() != null
        && event.runId().equals(cycle.mirrorRunId())
        && !event.successful();
  }

  private void submitDelayClosure(InFlightCycle cycle) {
    platformAsyncExecutor.execute(() -> executeDelayClosure(cycle));
  }

  private void executeDelayClosure(InFlightCycle cycle) {
    GitlabSyncConfig config = cycle.config();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    try {
      factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
      if (cycle.writebackEnabled()) {
        int enqueued = queueService.enqueueCandidates(config);
        log.info(
            "Customer issue delay closure advanced, sourceInstance={}, enqueued={}",
            sourceInstance,
            enqueued);
      }
    } catch (RuntimeException error) {
      log.warn("Customer issue delay closure failed, sourceInstance={}", sourceInstance, error);
    }
  }

  private List<GitlabSyncConfig> enabledConfigs() {
    return configService.listConfigs().stream()
        .filter(config -> config.getId() != null)
        .filter(
            config ->
                Boolean.TRUE.equals(
                    config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled()))
        .toList();
  }

  /**
   * 一次编排的在飞状态。
   *
   * @param config 数据源配置，终态事件命中后据此推进阶段 3，避免再次查询配置
   * @param mirrorRunId 阶段 1 运行编号；{@code null} 表示本轮无需前置刷新
   * @param period 发起本次编排的调度周期序号，用于判断是否已跨周期需要重启
   * @param writebackEnabled 发起时的写回开关，决定阶段 3 是否登记写回候选
   */
  private record InFlightCycle(
      GitlabSyncConfig config, Long mirrorRunId, int period, boolean writebackEnabled) {}
}
