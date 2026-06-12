package com.data.collection.platform.entity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record ReviewDataRecordSaveRequest(
    @NotBlank String projectName,
    @NotBlank String title,
    @NotBlank String moduleName,
    @NotBlank String reviewType,
    @NotNull LocalDate reviewDate,
    @NotBlank String reviewOwner,
    @NotEmpty List<String> reviewExperts,
    @NotNull @Min(0) Integer reviewScalePages,
    @NotBlank String reviewProduct,
    @NotBlank String authorName,
    @NotBlank String reviewVersion,
    String notReachStandardReason,
    String sourceFileName,
    Double weightedDefectDensity,
    List<ReviewDataDescriptionSaveRequest> descriptions,
    List<ReviewDataContentSaveRequest> contents,
    Boolean createPendingProblemItems) {

  public ReviewDataRecordSaveRequest(
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      LocalDate reviewDate,
      String reviewOwner,
      List<String> reviewExperts,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion) {
    this(
        projectName,
        title,
        moduleName,
        reviewType,
        reviewDate,
        reviewOwner,
        reviewExperts,
        reviewScalePages,
        reviewProduct,
        authorName,
        reviewVersion,
        null,
        null,
        null,
        List.of(),
        List.of(),
        false);
  }

  public ReviewDataRecordSaveRequest(
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      LocalDate reviewDate,
      String reviewOwner,
      List<String> reviewExperts,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion,
      String notReachStandardReason,
      Boolean createPendingProblemItems) {
    this(
        projectName,
        title,
        moduleName,
        reviewType,
        reviewDate,
        reviewOwner,
        reviewExperts,
        reviewScalePages,
        reviewProduct,
        authorName,
        reviewVersion,
        notReachStandardReason,
        null,
        null,
        List.of(),
        List.of(),
        createPendingProblemItems);
  }
}
