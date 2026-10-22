package com.data.collection.platform.service;

import java.time.LocalDateTime;

/**
 * 一条来源级延期写回进度。
 *
 * <p>该记录只承载标识与进度：运行号、阶段、修订、游标、授权事实版本和租约；不保存事实快照或每轮历史。
 *
 * @param sourceInstance 来源实例
 * @param configId 当前绑定的数据源配置
 * @param stage 当前阶段
 * @param requestRevision 已收到的请求修订，用于合并重复触发
 * @param processedRevision 当前处理者领取到的请求修订
 * @param completedRevision 已结算的请求修订
 * @param mirrorRunId 本轮绑定的前置镜像运行；{@code null} 表示尚未绑定
 * @param authorizedFactVersion 本轮授权的来源事实版本
 * @param authorizationRevision 授予该授权的请求修订
 * @param enqueueCursorIssueId 已入队的最后一个 issue_id；{@code null} 表示从头开始
 * @param failureCount 连续失败次数，用于退避
 * @param nextCheckAt 下一次允许推进的时间
 * @param leaseOwner 进度处理者
 * @param leaseToken 进度领取令牌
 * @param leaseUntil 进度租约到期时间
 * @param lastError 最近一次等待或失败原因
 */
public record CustomerIssueDelayClosureProgress(
    long id,
    String sourceInstance,
    Long configId,
    CustomerIssueDelayClosureStage stage,
    long requestRevision,
    long processedRevision,
    long completedRevision,
    Long mirrorRunId,
    Long authorizedFactVersion,
    Long authorizationRevision,
    Long enqueueCursorIssueId,
    int failureCount,
    LocalDateTime nextCheckAt,
    String leaseOwner,
    String leaseToken,
    LocalDateTime leaseUntil,
    String lastError) {

  public boolean hasPendingRequest() {
    return requestRevision > completedRevision;
  }

  public boolean authorized() {
    return authorizedFactVersion != null && authorizationRevision != null;
  }
}
