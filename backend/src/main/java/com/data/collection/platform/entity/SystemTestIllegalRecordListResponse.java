package com.data.collection.platform.entity;

import java.util.List;

public record SystemTestIllegalRecordListResponse(
    List<SystemTestIllegalRecordRowResponse> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
