package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record ReviewDataProblemItemResponse(
    Long id,
    Long reviewRecordId,
    String reviewerName,
    Double workloadHours,
    String reviewCategory,
    String documentPosition,
    String problemCategory,
    String problemDescription,
    String suggestedSolution,
    String ownerName,
    String rejectionReason,
    String problemStatus,
    LocalDateTime updatedAt,
    String createdBy) {}
