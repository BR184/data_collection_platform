package com.data.collection.platform.service;

public record SystemTestIllegalRecordQueryRequest(
    IssueFactRecordListRequest listRequest,
    String testingPhase,
    String illegalReason,
    String authorName,
    String assigneeName,
    String filterGroupJson) {}
