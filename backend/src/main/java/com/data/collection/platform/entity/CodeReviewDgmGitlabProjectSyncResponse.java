package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record CodeReviewDgmGitlabProjectSyncResponse(
    boolean success,
    String status,
    String message,
    long recordCount,
    LocalDateTime startedAt,
    LocalDateTime finishedAt) {
}
