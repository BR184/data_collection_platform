package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record IssueSourceReadinessResponse(
    LocalDateTime generatedAt,
    long projectCount,
    long issueCount,
    long milestoneCount,
    long issuesWithMilestoneCount,
    long customerProjectCount,
    long customerProjectIssueCount,
    long customerLabelIssueCount,
    long systemTestIssueCount,
    List<IssueFactCountBreakdownResponse> topIssueProjects,
    List<String> warnings) {}
