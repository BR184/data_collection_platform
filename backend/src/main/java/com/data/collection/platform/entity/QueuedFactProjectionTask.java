package com.data.collection.platform.entity;

import java.time.LocalDateTime;

/** 已领取、受租约保护的一项稳定范围投影刷新任务。 */
public record QueuedFactProjectionTask(
    long id,
    long factRunId,
    long factBuildTaskId,
    FactProjectionScope scope,
    long targetGeneration,
    int retryCount,
    int maxRetryCount,
    String leaseOwner,
    LocalDateTime leaseUntil) {}
