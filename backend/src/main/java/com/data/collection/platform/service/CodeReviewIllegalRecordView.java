package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

record CodeReviewIllegalRecordView(
    String requestType,
    Long mergeRequestId,
    Integer mergeRequestIid,
    Long projectId,
    String mergeRequestContent,
    String mergeRequestLink,
    String owner,
    String projectName,
    String repositoryName,
    LocalDateTime mergedAt,
    String author,
    String mergedBy,
    String moduleName,
    String targetBranch,
    List<String> illegalTypes,
    String reviewerNames,
    String assigneeNames,
    String reviewStatus,
    Integer reviewDurationMinutes,
    String scanStatus,
    Integer scanBugCount,
    String annotationRateResult,
    String bugCountResult,
    Double commentRate,
    Integer defectCount,
    Integer addedLines,
    Integer reviewSpeedLocPerHour,
    Double defectDensityPerKloc,
    Double reviewEfficiencyPerHour) {
}
