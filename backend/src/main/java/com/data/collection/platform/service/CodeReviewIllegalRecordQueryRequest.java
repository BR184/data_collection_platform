package com.data.collection.platform.service;

public record CodeReviewIllegalRecordQueryRequest(
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
    String filterGroupJson,
    int page,
    int size,
    String sortField,
    String sortOrder,
    String ruleConfigJson) {}
