package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 延期标签写回前的镜像增量刷新：只负责提交阶段 1 运行。
 *
 * <p>写回差异由延期事实与 GitLab 当前标签比对得出，因此必须先刷新镜像，并等本次增量被事实消费者
 * 发布到 {@code issue_fact} 之后才能比对。本类只承担"提交刷新运行"这一半职责：提交是同步的、
 * 有界的；等待发布收敛由 {@link CustomerIssueDelayClosureOrchestrator} 通过运行终态事件推进，
 * 不再由本类轮询——轮询会占用线程，且任何固定超时都与内网规模无关（镜像耗时超过阈值就整轮跳过）。
 */
@Service
@Slf4j
public class CustomerIssueDelayPreWritebackSyncService {
  private static final String SUBMISSION_REASON = "客户问题延期标签写回前增量刷新";

  private final SyncRunSubmissionService submissionService;
  private final GitlabMirrorProperties properties;

  public CustomerIssueDelayPreWritebackSyncService(
      SyncRunSubmissionService submissionService, GitlabMirrorProperties properties) {
    this.submissionService = submissionService;
    this.properties = properties;
  }

  /**
   * 提交写回前的镜像增量刷新运行。
   *
   * @param config 当前数据源配置
   * @return 提交结果：{@link Outcome.NotRequired} 表示开关关闭、无需前置刷新，调用方可以直接推进；
   *     {@link Outcome.Submitted} 携带已入队运行的编号，调用方据此等待该运行的终态事件；
   *     {@link Outcome.Rejected} 表示配置不具备刷新条件，本轮必须放弃写回
   */
  public Outcome submitPreWritebackSync(GitlabSyncConfig config) {
    if (!properties.isCustomerIssueDelayPreWritebackSyncEnabled()) {
      return new Outcome.NotRequired();
    }
    List<String> sourceTables = preWritebackSyncTables();
    if (sourceTables.isEmpty()) {
      log.warn("Customer issue delay pre-writeback sync skipped because no source tables were configured");
      return new Outcome.Rejected("未配置写回前增量刷新的来源表");
    }
    SyncRunSubmissionResult submission =
        submissionService.submitTableRefresh(config, sourceTables, SUBMISSION_REASON);
    return new Outcome.Submitted(submission.runId());
  }

  private List<String> preWritebackSyncTables() {
    String configuredTables = properties.getCustomerIssueDelayPreWritebackSyncTables();
    if (!StringUtils.hasText(configuredTables)) {
      return List.of();
    }
    return Arrays.stream(configuredTables.split(","))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .map(table -> table.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  /** 写回前增量刷新的提交结果。 */
  public sealed interface Outcome {
    /** 前置刷新开关关闭，无需提交运行。 */
    record NotRequired() implements Outcome {}

    /** 已提交镜像刷新运行。 */
    record Submitted(long runId) implements Outcome {}

    /** 不具备提交条件，本轮写回必须放弃。 */
    record Rejected(String reason) implements Outcome {}
  }
}
