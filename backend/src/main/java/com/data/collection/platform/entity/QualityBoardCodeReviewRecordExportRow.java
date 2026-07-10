package com.data.collection.platform.entity;

public record QualityBoardCodeReviewRecordExportRow(
    String source,
    String projectName,
    String repositoryName,
    Long mergeRequestIid,
    String title,
    String authorName,
    String assigneeNames,
    String targetBranch,
    String mergeRequestState,
    Integer addedLines,
    Integer defectCount,
    Double reviewDefectDensityPerKloc) {}
