package com.data.collection.platform.service;

record CodeReviewIllegalRecordSourcePageQuery(
    CodeReviewIllegalRecordQueryRequest request,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
