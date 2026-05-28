package com.data.collection.platform.service;

public record ReviewDataLegacyExcelConfirmRequest(
    String previewToken,
    String duplicateStrategy) {}
