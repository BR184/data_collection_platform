package com.data.collection.platform.entity;

import java.util.List;

public record ReviewDataGitlabContextRefreshRequest(
    List<Long> recordIds,
    String resourceType) {

  public ReviewDataGitlabContextRefreshRequest {
    recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
  }
}
