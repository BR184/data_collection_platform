package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemSaveRequest;
import com.data.collection.platform.entity.ReviewDataRecordSaveRequest;
import java.util.List;

public record ReviewDataLegacyExcelPreviewRowResponse(
    int rowNumber,
    boolean importable,
    ReviewDataRecordSaveRequest record,
    List<ReviewDataProblemItemSaveRequest> problemItems,
    List<ReviewDataLegacyExcelImportIssue> issues) {

  public ReviewDataLegacyExcelPreviewRowResponse {
    problemItems = problemItems == null ? List.of() : List.copyOf(problemItems);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
