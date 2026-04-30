package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record FactBuildTaskResponse(
    Long id,
    String runId,
    String scope,
    boolean full,
    String status,
    String triggerType,
    String lockOwner,
    int affectedRows,
    String message,
    String errorMessage,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
