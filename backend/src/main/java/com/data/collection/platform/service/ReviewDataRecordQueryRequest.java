package com.data.collection.platform.service;

import com.data.collection.platform.entity.TagSelectionRequest;
import java.util.List;

public record ReviewDataRecordQueryRequest(
    String keyword,
    String title,
    String projectName,
    String moduleName,
    String reviewOwner,
    String reviewType,
    String problemStatus,
    String reviewExpert,
    String filterGroupJson,
    List<TagSelectionRequest> tagSelections,
    int page,
    int size,
    String sortField,
    String sortOrder) {
  public ReviewDataRecordQueryRequest {
    tagSelections = tagSelections == null ? List.of() : List.copyOf(tagSelections);
  }

  public ReviewDataRecordQueryRequest(
      String keyword,
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String filterGroupJson,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    this(
        keyword,
        title,
        projectName,
        moduleName,
        reviewOwner,
        reviewType,
        problemStatus,
        reviewExpert,
        filterGroupJson,
        List.of(),
        page,
        size,
        sortField,
        sortOrder);
  }
}
