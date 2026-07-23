package com.data.collection.platform.service;

public record CustomerIssueRecordQueryRequest(
    String topic,
    IssueFactRecordListRequest listRequest,
    String reasonCategory,
    String authorName,
    String handlerName,
    String assigneeName,
    String testingPhase,
    String fixUser,
    String delayCause,
    String filterGroupJson,
    String customerName) {}
