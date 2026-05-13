package com.data.collection.platform.entity;

import java.util.List;

public record ReviewDataGitlabContextRefreshResponse(
    boolean accepted,
    Long jobId,
    String status,
    List<String> resourceTypes,
    List<String> sourceTables,
    int plannedTasks,
    boolean manualFieldsTouched,
    String message) {

  public ReviewDataGitlabContextRefreshResponse {
    resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
    sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
  }
}
