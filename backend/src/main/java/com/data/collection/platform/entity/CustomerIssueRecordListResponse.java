package com.data.collection.platform.entity;

import java.util.List;

public record CustomerIssueRecordListResponse(
    List<CustomerIssueRecordRowResponse> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
