package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;

record CodeReviewIllegalRecordSourcePageQuery(
    CodeReviewIllegalRecordQueryRequest request,
    StatisticFilterGroup filterGroup,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
