package com.data.collection.platform.service;

import java.util.List;

public record ReviewDataLegacyExcelPreviewResponse(
    String previewToken,
    String sheetName,
    int totalRows,
    int importableRows,
    int warningRows,
    int errorRows,
    int estimatedRecordCount,
    int estimatedProblemItemCount,
    List<ReviewDataLegacyExcelPreviewRowResponse> rows,
    List<ReviewDataLegacyExcelImportIssue> issues) {

  public ReviewDataLegacyExcelPreviewResponse {
    rows = rows == null ? List.of() : List.copyOf(rows);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
