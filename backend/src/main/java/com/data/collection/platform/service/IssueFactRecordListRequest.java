package com.data.collection.platform.service;

public record IssueFactRecordListRequest(
    Long projectId,
    String keyword,
    String issueIid,
    String title,
    String projectName,
    String moduleName,
    String severityLevel,
    String priorityLevel,
    String issueState,
    String bugStatus,
    String category,
    String milestoneTitle,
    String createdAtStart,
    String createdAtEnd,
    String updatedAtStart,
    String updatedAtEnd,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
