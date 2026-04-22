package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

public record IssueScopeContext(
    Long projectId,
    String projectName,
    String milestoneTitle,
    String testingPhase,
    String systemTestLabel,
    LocalDateTime createdAt,
    List<String> labels) {

  public IssueScopeContext {
    labels = labels == null ? List.of() : List.copyOf(labels);
  }
}
