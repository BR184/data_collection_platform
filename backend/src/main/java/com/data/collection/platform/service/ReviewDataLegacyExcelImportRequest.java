package com.data.collection.platform.service;

import java.time.LocalDate;
import java.util.List;

public record ReviewDataLegacyExcelImportRequest(
    LocalDate defaultReviewDate,
    String defaultReviewOwner,
    List<String> defaultReviewExperts,
    String defaultAuthorName,
    String defaultReviewVersion,
    String defaultProblemStatus,
    String duplicateStrategy) {

  public ReviewDataLegacyExcelImportRequest {
    defaultReviewExperts = defaultReviewExperts == null ? List.of() : List.copyOf(defaultReviewExperts);
  }
}
