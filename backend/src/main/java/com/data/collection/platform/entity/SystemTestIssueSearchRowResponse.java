package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record SystemTestIssueSearchRowResponse(
    Long issueId,
    Integer issueIid,
    String issueLink,
    String sourceInstance,
    Long projectId,
    String projectName,
    String title,
    String issueState,
    String testingPhase,
    String severityLevel,
    String priorityLevel,
    String bugStatus,
    String category,
    String milestoneTitle,
    String delayCause,
    String authorName,
    String assigneeName,
    String moduleNames,
    String functionName,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime closedAt,
    List<String> labels) {}
