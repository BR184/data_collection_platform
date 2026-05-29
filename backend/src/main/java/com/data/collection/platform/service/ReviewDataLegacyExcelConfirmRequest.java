package com.data.collection.platform.service;

import java.time.LocalDate;
import java.util.List;

public record ReviewDataLegacyExcelConfirmRequest(
    String previewToken,
    String duplicateStrategy,
    LocalDate defaultReviewDate,
    String defaultReviewOwner,
    List<String> defaultReviewExperts,
    String defaultAuthorName,
    String defaultReviewVersion,
    String defaultProblemStatus) {

  public ReviewDataLegacyExcelConfirmRequest {
    defaultReviewExperts = defaultReviewExperts == null ? List.of() : List.copyOf(defaultReviewExperts);
  }
}
