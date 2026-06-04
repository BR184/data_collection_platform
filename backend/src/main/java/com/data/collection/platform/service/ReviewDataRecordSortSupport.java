package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;

final class ReviewDataRecordSortSupport {

  private ReviewDataRecordSortSupport() {}

  static Comparator<ReviewDataRecordRowResponse> buildComparator(String sortField, String sortOrder) {
    Comparator<ReviewDataRecordRowResponse> comparator =
        switch (sortField) {
          case "projectName" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::projectName,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "moduleName" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::moduleName,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewType" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewType,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewDate" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewDate, Comparator.nullsLast(LocalDate::compareTo));
          case "reviewOwner" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewOwner,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewScalePages" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewScalePages, Comparator.nullsLast(Integer::compareTo));
          case "problemCount" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::problemCount, Comparator.nullsLast(Integer::compareTo));
          case "problemDensity" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::problemDensity, Comparator.nullsLast(Double::compareTo));
          case "reviewEfficiency" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewEfficiency, Comparator.nullsLast(Double::compareTo));
          case "reviewRate" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewRate, Comparator.nullsLast(Double::compareTo));
          case "independentReviewWorkload" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::independentReviewWorkload,
                  Comparator.nullsLast(Double::compareTo));
          case "independentReviewProblemCount" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::independentReviewProblemCount,
                  Comparator.nullsLast(Integer::compareTo));
          case "meetingReviewWorkload" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::meetingReviewWorkload,
                  Comparator.nullsLast(Double::compareTo));
          case "meetingReviewProblemCount" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::meetingReviewProblemCount,
                  Comparator.nullsLast(Integer::compareTo));
          case "reachStandard" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reachStandard,
                  Comparator.nullsLast(Boolean::compareTo));
          case "createdAt" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::createdAt,
                  Comparator.nullsLast(LocalDateTime::compareTo));
          case "updatedAt" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::updatedAt,
                  Comparator.nullsLast(LocalDateTime::compareTo));
          default ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::title,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
    if ("desc".equalsIgnoreCase(sortOrder)) {
      comparator = comparator.reversed();
    }
    return comparator.thenComparing(ReviewDataRecordRowResponse::id, Comparator.nullsLast(Long::compareTo));
  }

  static String normalizeSortField(String sortField) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    if (normalized == null) {
      return "updatedAt";
    }
    return switch (normalized) {
      case "title",
          "projectName",
          "moduleName",
          "reviewType",
          "reviewDate",
          "reviewOwner",
          "reviewScalePages",
          "problemCount",
          "problemDensity",
          "reviewEfficiency",
          "reviewRate",
          "independentReviewWorkload",
          "independentReviewProblemCount",
          "meetingReviewWorkload",
          "meetingReviewProblemCount",
          "reachStandard",
          "createdAt",
          "updatedAt" -> normalized;
      default -> "updatedAt";
    };
  }

  static String normalizeSortOrder(String sortOrder) {
    String normalized = TextQuerySupport.trimToNull(sortOrder);
    if ("asc".equalsIgnoreCase(normalized)) {
      return "asc";
    }
    return "desc";
  }
}
