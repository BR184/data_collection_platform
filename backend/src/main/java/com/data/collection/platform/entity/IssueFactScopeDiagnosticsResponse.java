package com.data.collection.platform.entity;

public record IssueFactScopeDiagnosticsResponse(
    String scopeKey,
    String scopeLabel,
    long totalCount,
    long withReasonCategoryCount,
    long withMilestoneTitleCount,
    long withTemplateReplyCount,
    long responseDelayedCount,
    long resolveDelayedCount) {}
