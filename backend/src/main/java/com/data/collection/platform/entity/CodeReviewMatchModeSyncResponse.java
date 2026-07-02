package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record CodeReviewMatchModeSyncResponse(
    boolean accepted,
    String status,
    String message,
    long recordCount,
    LocalDateTime startedAt,
    LocalDateTime finishedAt) {
}
