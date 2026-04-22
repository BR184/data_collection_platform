package com.data.collection.platform.entity;

import java.util.List;

public record CustomerIssueIllegalRecordListResponse(
    List<CustomerIssueIllegalRecordRowResponse> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
