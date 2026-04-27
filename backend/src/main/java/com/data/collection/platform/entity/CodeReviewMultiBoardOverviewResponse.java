package com.data.collection.platform.entity;

import java.util.List;

public record CodeReviewMultiBoardOverviewResponse(
    String source,
    String sourceLabel,
    int mergeRequestCount,
    int completedCount,
    int pendingCount,
    Double averageCommentRate,
    int totalDefectCount,
    Double averageReviewDurationMinutes,
    Double averageAddedLines,
    List<CodeReviewMultiBoardBreakdownRowResponse> moduleRows,
    List<CodeReviewMultiBoardBreakdownRowResponse> ownerRows) {}
