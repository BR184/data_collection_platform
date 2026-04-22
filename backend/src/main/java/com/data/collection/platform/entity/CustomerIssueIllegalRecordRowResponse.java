package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record CustomerIssueIllegalRecordRowResponse(
    Long issueId,
    Integer issueIid,
    Long projectId,
    String projectName,
    String title,
    String issueState,
    String illegalReason,
    String severityLevel,
    String priorityLevel,
    String bugStatus,
    String category,
    String milestoneTitle,
    String authorName,
    String assigneeName,
    String moduleNames,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime closedAt,
    List<String> labels) {}
