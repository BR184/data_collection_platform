package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record CodeReviewDgmGitlabProjectSourceResponse(
    boolean enabled,
    String gitlabBaseUrl,
    boolean accessTokenConfigured,
    String groupPath,
    boolean includeSubgroups,
    boolean includeArchived,
    int syncIntervalMinutes,
    String lastSyncStatus,
    String lastSyncMessage,
    long lastSyncRecordCount,
    LocalDateTime lastSyncStartedAt,
    LocalDateTime lastSyncFinishedAt,
    LocalDateTime updatedAt) {
}
