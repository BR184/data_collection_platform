package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

record CustomerIssueDelayLabelWritebackJob(
    Long id,
    String sourceInstance,
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
    Integer lastHttpStatus) {
  boolean hasChanges() {
    return !addLabels.isEmpty() || !removeLabels.isEmpty();
  }
}
