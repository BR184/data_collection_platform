package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewRuleConfig;

public record CodeReviewRulePreviewRequest(
    Long projectId,
    String repositoryName,
    String mergedAtStart,
    String mergedAtEnd,
    String keyword,
    String projectName,
    String requestType,
    String targetBranch,
    String mergedBy,
    String moduleName,
    String illegalType,
    String mergeRequestIid,
    String owner,
    String source,
    CodeReviewRuleConfig ruleConfig) {
}
