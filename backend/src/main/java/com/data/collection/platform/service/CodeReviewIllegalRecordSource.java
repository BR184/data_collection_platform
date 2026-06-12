package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

record CodeReviewIllegalRecordSource(
    Long mergeRequestId,
    Integer mergeRequestIid,
    Long projectId,
    String mergeRequestContent,
    String projectName,
    String repositoryName,
    LocalDateTime mergedAt,
    String author,
    String mergedBy,
    String owner,
    String reviewerNames,
    String assigneeNames,
    String targetBranch,
    String moduleName,
    List<String> labelTitles,
    String reviewStatus,
    Integer reviewDurationMinutes,
    String scanStatus,
    Integer scanBugCount,
    String annotationRateResult,
    String bugCountResult,
    Double commentRate,
    Integer defectCount,
    Integer addedLines) {
}
