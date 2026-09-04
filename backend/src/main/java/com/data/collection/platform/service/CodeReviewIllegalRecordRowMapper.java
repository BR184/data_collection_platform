package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class CodeReviewIllegalRecordRowMapper {
  CodeReviewIllegalRecordSource mapFactSource(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new CodeReviewIllegalRecordSource(
        resultSet.getString("source_instance"),
        resultSet.getLong("merge_request_id"),
        resultSet.getInt("merge_request_iid"),
        resultSet.getLong("project_id"),
        resultSet.getString("merge_request_content"),
        resultSet.getString("project_name"),
        resultSet.getString("repository_name"),
        toLocalDateTime(resultSet, "merged_at"),
        resultSet.getString("author"),
        resultSet.getString("merged_by"),
        resultSet.getString("owner"),
        resultSet.getString("reviewer_names"),
        resultSet.getString("assignee_names"),
        resultSet.getString("target_branch"),
        resultSet.getString("module_name"),
        splitLabels(resultSet.getString("label_names")),
        resultSet.getString("review_status"),
        (Integer) resultSet.getObject("review_duration_minutes"),
        resultSet.getString("review_exception_reason"),
        toLocalDateTime(resultSet, "code_walkthrough_date"),
        resultSet.getString("scan_status"),
        (Integer) resultSet.getObject("scan_bug_count"),
        resultSet.getString("annotation_rate_result"),
        resultSet.getString("bug_count_result"),
        toDouble(resultSet.getObject("comment_rate")),
        (Integer) resultSet.getObject("defect_count"),
        (Integer) resultSet.getObject("added_lines"),
        (Integer) resultSet.getObject("deleted_lines"),
        (Integer) resultSet.getObject("code_specification_count"),
        (Integer) resultSet.getObject("code_logic_specification_count"),
        (Integer) resultSet.getObject("performance_specification_count"),
        (Integer) resultSet.getObject("design_specification_count"),
        (Integer) resultSet.getObject("other_specification_count"),
        (Integer) resultSet.getObject("review_speed_loc_per_hour"),
        toDouble(resultSet.getObject("review_speed_kloc_per_hour")),
        toDouble(resultSet.getObject("review_defect_density_per_kloc")),
        toDouble(resultSet.getObject("review_efficiency_per_hour")),
        (Integer) resultSet.getObject("commit_count"),
        (Integer) resultSet.getObject("commit_rate"),
        resultSet.getString("function_name"),
        (Integer) resultSet.getObject("clang_added_line_count"));
  }

  private java.time.LocalDateTime toLocalDateTime(ResultSet resultSet, String columnName)
      throws SQLException {
    java.sql.Timestamp timestamp = resultSet.getTimestamp(columnName);
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private Double toDouble(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    return Double.valueOf(String.valueOf(value));
  }

  private List<String> splitLabels(String labelNames) {
    if (!StringUtils.hasText(labelNames)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String value : labelNames.split(",")) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }
}
