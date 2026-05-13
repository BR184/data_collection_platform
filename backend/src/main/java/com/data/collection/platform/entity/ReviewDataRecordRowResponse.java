package com.data.collection.platform.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReviewDataRecordRowResponse(
    Long id,
    String projectName,
    String title,
    String moduleName,
    String reviewType,
    LocalDate reviewDate,
    String reviewOwner,
    String reviewExpertsSummary,
    Integer reviewScalePages,
    String reviewProduct,
    String authorName,
    String reviewVersion,
    Integer problemCount,
    Double problemDensity,
    LocalDateTime updatedAt,
    boolean deleted,
    Long gitlabProjectId,
    Long gitlabResourceIid,
    String gitlabResourceType) {

  public ReviewDataRecordRowResponse(
      Long id,
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      LocalDate reviewDate,
      String reviewOwner,
      String reviewExpertsSummary,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion,
      Integer problemCount,
      Double problemDensity,
      LocalDateTime updatedAt,
      boolean deleted) {
    this(
        id,
        projectName,
        title,
        moduleName,
        reviewType,
        reviewDate,
        reviewOwner,
        reviewExpertsSummary,
        reviewScalePages,
        reviewProduct,
        authorName,
        reviewVersion,
        problemCount,
        problemDensity,
        updatedAt,
        deleted,
        null,
        null,
        null);
  }
}
