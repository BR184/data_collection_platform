package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataSummaryService {
  public ReviewDataSummaryResponse buildSummary(List<ReviewDataRecordRowResponse> rows) {
    long totalRecords = rows.size();
    long totalProblemItems =
        rows.stream()
            .map(ReviewDataRecordRowResponse::problemCount)
            .filter(Objects::nonNull)
            .mapToLong(Integer::longValue)
            .sum();
    double averageReviewScalePages =
        average(rows.stream().map(ReviewDataRecordRowResponse::reviewScalePages).toList());
    double averageProblemCount =
        average(rows.stream().map(ReviewDataRecordRowResponse::problemCount).toList());
    return new ReviewDataSummaryResponse(
        totalRecords, totalProblemItems, averageReviewScalePages, averageProblemCount);
  }

  private double average(List<? extends Number> values) {
    List<Double> normalized =
        values.stream().filter(Objects::nonNull).map(Number::doubleValue).toList();
    if (normalized.isEmpty()) {
      return 0D;
    }
    return normalized.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
  }
}
