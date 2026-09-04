package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

@Component
class ReviewDataRecordRowMapper {
  ReviewDataRecordRowResponse mapRecordRow(ResultSet resultSet, int rowNumber)
      throws SQLException {
    Integer reviewScalePages = (Integer) resultSet.getObject("review_scale_pages");
    Integer problemCount = (Integer) resultSet.getObject("problem_count");
    Double reviewEfficiency = getDoubleOrDefault(resultSet, "review_efficiency");
    Double reviewRate = getDoubleOrDefault(resultSet, "review_rate");
    return new ReviewDataRecordRowResponse(
        resultSet.getLong("id"),
        TextQuerySupport.normalizeDisplay(resultSet.getString("project_name")),
        TextQuerySupport.normalizeDisplay(resultSet.getString("title")),
        ReviewDataModuleNameSupport.normalize(resultSet.getString("module_name")),
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_type")),
        resultSet.getDate("review_date") == null
            ? null
            : resultSet.getDate("review_date").toLocalDate(),
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_owner")),
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_experts_summary")),
        reviewScalePages,
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_product")),
        TextQuerySupport.normalizeDisplay(resultSet.getString("author_name")),
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_version")),
        problemCount == null ? 0 : problemCount,
        calculateProblemDensity(problemCount, reviewScalePages),
        reviewEfficiency,
        reviewRate,
        TextQuerySupport.normalizeDisplay(resultSet.getString("review_category_summary")),
        getIntegerOrDefault(resultSet, "doc_specification_count"),
        getIntegerOrDefault(resultSet, "integrity_count"),
        getIntegerOrDefault(resultSet, "functionality_count"),
        getIntegerOrDefault(resultSet, "feasibility_count"),
        getDoubleOrDefault(resultSet, "independent_review_workload"),
        getIntegerOrDefault(resultSet, "independent_review_problem_count"),
        getDoubleOrDefault(resultSet, "meeting_review_workload"),
        getIntegerOrDefault(resultSet, "meeting_review_problem_count"),
        TextQuerySupport.normalizeDisplay(resultSet.getString("not_reach_standard_reason")),
        isReachStandard(problemCount, reviewScalePages),
        TextQuerySupport.normalizeDisplay(resultSet.getString("source_file_name")),
        getDoubleOrNull(resultSet, "weighted_defect_density"),
        toLocalDateTime(resultSet, "created_at"),
        toLocalDateTime(resultSet, "updated_at"),
        resultSet.getBoolean("deleted"),
        (Long) resultSet.getObject("gitlab_project_id"),
        (Long) resultSet.getObject("gitlab_resource_iid"),
        TextQuerySupport.trimToNull(resultSet.getString("gitlab_resource_type")),
        TextQuerySupport.trimToNull(resultSet.getString("created_by")));
  }

  private Double calculateProblemDensity(Integer problemCount, Integer reviewScalePages) {
    if (problemCount == null || reviewScalePages == null || reviewScalePages <= 0) {
      return 0D;
    }
    return ReviewDataNumberSupport.roundToTwoDecimals(
        problemCount.doubleValue() / reviewScalePages.doubleValue());
  }

  private Boolean isReachStandard(Integer problemCount, Integer reviewScalePages) {
    Double density = calculateProblemDensity(problemCount, reviewScalePages);
    return density >= 0.2D && density <= 0.6D;
  }

  private Double getDoubleOrDefault(ResultSet resultSet, String columnName) throws SQLException {
    Object value = resultSet.getObject(columnName);
    return value == null ? 0D : resultSet.getDouble(columnName);
  }

  private Double getDoubleOrNull(ResultSet resultSet, String columnName) throws SQLException {
    Object value = resultSet.getObject(columnName);
    return value == null ? null : resultSet.getDouble(columnName);
  }

  private Integer getIntegerOrDefault(ResultSet resultSet, String columnName)
      throws SQLException {
    Object value = resultSet.getObject(columnName);
    return value == null ? 0 : resultSet.getInt(columnName);
  }

  private java.time.LocalDateTime toLocalDateTime(ResultSet resultSet, String columnName)
      throws SQLException {
    java.sql.Timestamp timestamp = resultSet.getTimestamp(columnName);
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }
}
