package com.data.collection.platform.service;

public record ReviewDataLegacyExcelImportIssue(
    int rowNumber,
    String field,
    ReviewDataLegacyExcelIssueLevel level,
    String message) {}
