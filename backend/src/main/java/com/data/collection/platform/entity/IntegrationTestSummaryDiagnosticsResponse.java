package com.data.collection.platform.entity;

public record IntegrationTestSummaryDiagnosticsResponse(
    long totalParsedRows,
    long includedModuleRows,
    long excludedMissingModuleRows) {
}
