package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record IntegrationTestSummaryResponse(
    Long projectId,
    String testingPhase,
    long moduleCount,
    long totalIssueCount,
    LocalDateTime factRefreshedAt,
    List<IntegrationTestSummaryRowResponse> rows,
    IntegrationTestSummaryDiagnosticsResponse diagnostics) {

  public IntegrationTestSummaryResponse(
      Long projectId,
      String testingPhase,
      long moduleCount,
      long totalIssueCount,
      LocalDateTime factRefreshedAt,
      List<IntegrationTestSummaryRowResponse> rows) {
    this(
        projectId,
        testingPhase,
        moduleCount,
        totalIssueCount,
        factRefreshedAt,
        rows,
        new IntegrationTestSummaryDiagnosticsResponse(totalIssueCount, totalIssueCount, 0));
  }
}
