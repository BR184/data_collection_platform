package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条延期标签写回作业。
 *
 * <p>{@code roundMirrorRunId / authorizationRevision / requiredFactVersion} 绑定产生本作业的写回轮次；
 * {@code claimToken / claimRevision / taskRevision} 是该行当前的领取与修订身份。结算必须同时匹配
 * 令牌、领取修订和任务修订，处理期间到达的新差异会推进任务修订，使旧执行结果无法清除新工作。
 */
record CustomerIssueDelayLabelWritebackJob(
    Long id,
    String sourceInstance,
    Long configId,
    Long projectId,
    Long issueIid,
    Long issueId,
    boolean desiredResponseDelayed,
    boolean desiredResolveDelayed,
    String currentLabelNames,
    List<String> addLabels,
    List<String> removeLabels,
    String status,
    int attemptCount,
    int maxAttempts,
    LocalDateTime nextRunAt,
    String leaseOwner,
    LocalDateTime leaseUntil,
    String lastError,
    Integer lastHttpStatus,
    Long roundMirrorRunId,
    Long authorizationRevision,
    Long requiredFactVersion,
    String claimToken,
    long claimRevision,
    long taskRevision) {
  boolean hasChanges() {
    return !addLabels.isEmpty() || !removeLabels.isEmpty();
  }
}
