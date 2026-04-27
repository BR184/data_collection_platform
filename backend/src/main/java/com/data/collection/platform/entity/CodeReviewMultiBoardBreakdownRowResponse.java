package com.data.collection.platform.entity;

public record CodeReviewMultiBoardBreakdownRowResponse(
    String rowKey,
    String rowLabel,
    int mergeRequestCount,
    int completedCount,
    Double averageCommentRate,
    int totalDefectCount,
    Double averageReviewDurationMinutes,
    Double averageAddedLines) {}
