package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record CustomerIssueRecordRowResponse(
    Long issueId,
    Integer issueIid,
    Long projectId,
    String projectName,
    String title,
    String issueState,
    String severityLevel,
    String priorityLevel,
    String bugStatus,
    String category,
    String reasonCategory,
    String milestoneTitle,
    String authorName,
    String assigneeName,
    String moduleNames,
    boolean delayIssue,
    String delayReason,
    String delayCause,
    boolean responseDelayed,
    boolean resolveDelayed,
    boolean illegal,
    String illegalReason,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime closedAt,
    List<String> labels) {}
