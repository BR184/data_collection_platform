package com.data.collection.platform.service;

public record CustomerIssueRecordQueryRequest(
    String topic,
    IssueFactRecordListRequest listRequest,
    String reasonCategory,
    String authorName,
    String assigneeName,
    String filterGroupJson) {}
