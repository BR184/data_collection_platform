package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record ReviewDataContentResponse(
    Long id,
    Long reviewRecordId,
    String reviewerName,
    String assignmentContent,
    Double independentWorkloadHours,
    Integer independentProblemCount,
    Double meetingWorkloadHours,
    Integer meetingProblemCount,
    Integer sortOrder,
    LocalDateTime updatedAt) {}
