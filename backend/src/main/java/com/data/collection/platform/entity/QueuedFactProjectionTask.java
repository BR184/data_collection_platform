package com.data.collection.platform.entity;

import java.time.LocalDateTime;

/**
 * 已领取、受租约保护的一项稳定范围投影刷新任务。
 *
 * <p>同时携带两层执行权身份：{@code leaseToken} 是本次任务领取生成的令牌，{@code factRunLeaseToken}
 * 是授权该任务的父 {@code FACT_REFRESH} 运行的执行令牌。快照写入与任务终态都必须同时验证两者。
 */
public record QueuedFactProjectionTask(
    long id,
    long factRunId,
    String factRunLeaseToken,
    long factBuildTaskId,
    FactProjectionScope scope,
    long targetGeneration,
    int retryCount,
    int maxRetryCount,
    String leaseToken,
    LocalDateTime leaseUntil) {}
