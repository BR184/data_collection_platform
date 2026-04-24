package com.data.collection.platform.service;

public record ReviewDataRecordQueryRequest(
    String keyword,
    String title,
    String projectName,
    String moduleName,
    String reviewOwner,
    String reviewType,
    String problemStatus,
    String reviewExpert,
    String filterGroupJson,
    int page,
    int size,
    String sortField,
    String sortOrder) {}
