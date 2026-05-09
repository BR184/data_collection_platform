package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record QueuedFactBuildTask(
    Long id,
    Long configId,
    String sourceInstance,
    String factType,
    String scope,
    boolean full,
    int retryCount,
    int maxRetryCount,
    LocalDateTime leaseUntil) {
}
