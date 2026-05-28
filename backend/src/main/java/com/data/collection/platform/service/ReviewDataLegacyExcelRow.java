package com.data.collection.platform.service;

import java.time.LocalDate;
import java.util.List;

public record ReviewDataLegacyExcelRow(
    int rowNumber,
    String title,
    String projectName,
    String moduleName,
    String reviewType,
    String reviewCategoryText,
    LocalDate reviewDate,
    String reviewOwner,
    Integer reviewScalePages,
    Integer problemCount,
    Integer docSpecificationCount,
    Integer integrityCount,
    Integer functionalityCount,
    Integer feasibilityCount,
    Double reviewDefectDensity,
    Double reviewEfficiency,
    Double reviewRate,
    Integer independentProblemCount,
    Integer meetingProblemCount,
    Double independentWorkloadHours,
    Double meetingWorkloadHours,
    String notReachStandardReason,
    List<ReviewDataLegacyExcelImportIssue> issues) {

  public ReviewDataLegacyExcelRow {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
