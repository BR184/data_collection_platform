package com.data.collection.platform.service;

public record CustomerIssueRecordQueryRequest(
    String topic,
    IssueFactRecordListRequest listRequest,
    String reasonCategory,
    String filterGroupJson) {}
