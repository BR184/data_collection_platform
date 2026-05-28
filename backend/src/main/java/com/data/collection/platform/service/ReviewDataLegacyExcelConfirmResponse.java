package com.data.collection.platform.service;

import java.util.List;

public record ReviewDataLegacyExcelConfirmResponse(
    int importedRecords,
    int skippedRecords,
    int importedProblemItems,
    List<ReviewDataLegacyExcelImportIssue> issues) {}
