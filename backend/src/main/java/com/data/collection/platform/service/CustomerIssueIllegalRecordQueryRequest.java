package com.data.collection.platform.service;

public record CustomerIssueIllegalRecordQueryRequest(
    IssueFactRecordListRequest listRequest,
    String illegalReason,
    String testingPhase,
    String authorName,
    String assigneeName,
    String filterGroupJson) {}
