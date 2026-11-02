package com.data.collection.platform.entity;

import java.util.List;

public record CodeReviewMultiBoardBreakdownRowResponse(
    String rowKey,
    String rowLabel,
    int mergeRequestCount,
    int completedCount,
    Double averageCommentRate,
    int totalDefectCount,
    int totalAddedLines,
    Double defectDensityPerKloc,
    Double averageReviewDurationMinutes,
    Double averageAddedLines,
    List<CodeReviewDurationPartitionResponse> reviewDurationPartitions) {}
