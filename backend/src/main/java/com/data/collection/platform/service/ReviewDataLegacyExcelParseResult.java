package com.data.collection.platform.service;

import java.util.List;

public record ReviewDataLegacyExcelParseResult(
    String sheetName,
    List<ReviewDataLegacyExcelRow> rows,
    List<ReviewDataLegacyExcelImportIssue> issues) {

  public ReviewDataLegacyExcelParseResult {
    rows = rows == null ? List.of() : List.copyOf(rows);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
