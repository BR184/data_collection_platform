package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record SystemTestIllegalRecordRowResponse(
    Long issueId,
    Integer issueIid,
    String issueLink,
    Long projectId,
    String projectName,
    String title,
    String issueState,
    String testingPhase,
    String illegalReason,
    String severityLevel,
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
