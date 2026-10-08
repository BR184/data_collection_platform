package com.data.collection.platform.entity;

import java.time.LocalDateTime;

/**
 * 已领取、受租约保护的一项事实构建任务。
 *
 * <p>同时携带两层执行权身份：{@code leaseOwner} 是本次任务领取生成的令牌，{@code factRunLeaseToken}
 * 是授权该任务的父 {@code FACT_REFRESH} 运行的执行令牌。批次提交必须同时验证两者，任何一层失效都
 * 说明当前执行者不再是权威写入方。
 */
public record QueuedFactBuildTask(
    Long id,
    Long factRunId,
    String factRunLeaseToken,
    Long configId,
    String sourceInstance,
    String factType,
    String scope,
    boolean full,
    int retryCount,
    int maxRetryCount,
    String leaseOwner,
    LocalDateTime leaseUntil) {
}
