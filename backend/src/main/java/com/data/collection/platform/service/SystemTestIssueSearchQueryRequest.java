package com.data.collection.platform.service;

import java.util.Arrays;
import java.util.List;

public record SystemTestIssueSearchQueryRequest(
    IssueFactRecordListRequest listRequest,
    String testingPhase,
    String authorName,
    String assigneeName,
    String filterGroupJson) {

  public List<String> testingPhases() {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    if (normalized == null) {
      return List.of();
    }
    return Arrays.stream(normalized.split(","))
        .map(TextQuerySupport::trimToNull)
        .filter(value -> value != null)
        .distinct()
        .toList();
  }
}
