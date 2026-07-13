package com.data.collection.platform.entity;

import java.util.List;

/** Lightweight selector data for the R&D quality dashboard. */
public record QualityBoardRdFilterOptionsResponse(
    String defaultProjectName,
    List<OptionItemResponse> projectOptions,
    List<OptionItemResponse> codeReviewSourceOptions) {
  public QualityBoardRdFilterOptionsResponse {
    projectOptions = projectOptions == null ? List.of() : List.copyOf(projectOptions);
    codeReviewSourceOptions =
        codeReviewSourceOptions == null ? List.of() : List.copyOf(codeReviewSourceOptions);
  }
}
