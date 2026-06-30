package com.data.collection.platform.service;

import java.time.LocalDateTime;

record IssueTemplateSnapshot(
    boolean hasTemplateReply,
    int resolveSlaDays,
    LocalDateTime planSolutionTime,
    int latestReasonCategoryCount,
    String normalizedReasonCategory,
    String legacyReasonText) {}
