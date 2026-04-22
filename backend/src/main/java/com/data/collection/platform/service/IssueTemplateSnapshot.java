package com.data.collection.platform.service;

record IssueTemplateSnapshot(
    boolean hasTemplateReply,
    int resolveSlaDays,
    int latestReasonCategoryCount,
    String normalizedReasonCategory) {}
