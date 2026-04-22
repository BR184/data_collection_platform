package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record IssueFactDiagnosticsResponse(
    LocalDateTime generatedAt,
    IssueFactScopeDiagnosticsResponse overall,
    IssueFactScopeDiagnosticsResponse systemTest,
    IssueFactScopeDiagnosticsResponse customerIssue,
    List<IssueFactCountBreakdownResponse> overallReasonCategories,
    List<IssueFactCountBreakdownResponse> customerIssueReasonCategories,
    List<IssueFactCountBreakdownResponse> customerIssueProjects) {}
