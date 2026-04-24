package com.data.collection.platform.service;

public record SystemTestIssueSearchQueryRequest(
    IssueFactRecordListRequest listRequest,
    String testingPhase,
    String authorName,
    String assigneeName) {}
