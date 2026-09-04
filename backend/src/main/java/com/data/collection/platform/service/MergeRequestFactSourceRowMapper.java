package com.data.collection.platform.service;

import static com.data.collection.platform.service.FactSourceRowValueSupport.defaultText;
import static com.data.collection.platform.service.FactSourceRowValueSupport.mergeRequestState;
import static com.data.collection.platform.service.FactSourceRowValueSupport.nullableLong;
import static com.data.collection.platform.service.FactSourceRowValueSupport.readTextArray;
import static com.data.collection.platform.service.FactSourceRowValueSupport.toLocalDateTime;

import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.service.ModuleDictionaryService.ModuleDictionary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class MergeRequestFactSourceRowMapper {
  private static final String DEFAULT_SOURCE_SYSTEM = "GITLAB";
  private static final String MIRROR_INGEST_CHANNEL = "MIRROR";

  MergeRequestFact mapSource(
      ResultSet resultSet, String sourceInstance, ModuleDictionary moduleDictionary)
      throws SQLException {
    List<String> labels = readTextArray(resultSet.getArray("label_titles"));
    List<String> projectLabels =
        readTextArray(resultSet.getArray("project_label_titles"));
    MergeRequestFact fact = new MergeRequestFact();
    fact.setSourceSystem(DEFAULT_SOURCE_SYSTEM);
    fact.setSourceInstance(sourceInstance);
    fact.setIngestChannel(MIRROR_INGEST_CHANNEL);
    fact.setSourceSummary(
        defaultText(
            resultSet.getString("metric_source_summary"),
            "GitLab merge request 镜像聚合"));
    fact.setRawPayload(defaultText(resultSet.getString("metric_raw_payload"), null));
    fact.setProjectId(resultSet.getLong("project_id"));
    fact.setProjectName(
        IssueFactNormalizationRules.normalizeMergeRequestProjectName(projectLabels));
    fact.setRepositoryName(defaultText(resultSet.getString("repository_name")));
    fact.setMergeRequestId(resultSet.getLong("merge_request_id"));
    fact.setMergeRequestIid(resultSet.getLong("merge_request_iid"));
    fact.setTitle(defaultText(resultSet.getString("title")));
    fact.setMergeRequestState(mergeRequestState(resultSet));
    fact.setTargetBranch(defaultText(resultSet.getString("target_branch")));
    fact.setSourceBranch(defaultText(resultSet.getString("source_branch")));
    fact.setAuthorName(defaultText(resultSet.getString("author_name")));
    fact.setMergeUserName(defaultText(resultSet.getString("merge_user_name")));
    fact.setOwnerName(defaultText(resultSet.getString("owner_name")));
    fact.setReviewerNames(defaultText(resultSet.getString("reviewer_names")));
    fact.setAssigneeNames(defaultText(resultSet.getString("assignee_names")));
    List<String> modules =
        moduleDictionary.normalizeMergeRequestModules(
            resultSet.getLong("project_id"),
            IssueFactNormalizationRules.normalizeMergeRequestModuleNames(labels));
    fact.setModuleName(String.join(" & ", modules));
    fact.setLabelNames(String.join(", ", labels));
    fact.setCreatedAtSource(toLocalDateTime(resultSet.getTimestamp("created_at")));
    fact.setUpdatedAtSource(toLocalDateTime(resultSet.getTimestamp("updated_at")));
    fact.setOdsUpdatedAt(toLocalDateTime(resultSet.getTimestamp("ods_updated_at")));
    fact.setMergedAtSource(toLocalDateTime(resultSet.getTimestamp("merged_at")));
    String reviewerNames = defaultText(resultSet.getString("reviewer_names"));
    boolean noNeedReview =
        "无需走查".equals(reviewerNames) || "无需走查扫描".equals(reviewerNames);
    fact.setReviewStatus(
        resultSet.getObject("review_duration_minutes") == null && !noNeedReview
            ? "PENDING"
            : "COMPLETED");
    fact.setReviewDurationMinutes(
        (Integer) resultSet.getObject("review_duration_minutes"));
    fact.setReviewExceptionReason(
        defaultText(resultSet.getString("review_exception_reason")));
    fact.setCodeWalkthroughDate(
        toLocalDateTime(resultSet.getTimestamp("code_walkthrough_date")));
    fact.setCommentRate((BigDecimal) resultSet.getObject("comment_rate"));
    fact.setCommentRateSource(defaultText(resultSet.getString("comment_rate_source")));
    fact.setDefectCount((Integer) resultSet.getObject("defect_count"));
    fact.setDefectCountSource(defaultText(resultSet.getString("defect_count_source")));
    fact.setScanStatus(defaultText(resultSet.getString("scan_status")));
    fact.setScanBugCount((Integer) resultSet.getObject("scan_bug_count"));
    fact.setAnnotationRateResult(
        defaultText(resultSet.getString("annotation_rate_result")));
    fact.setBugCountResult(defaultText(resultSet.getString("bug_count_result")));
    fact.setAddedLines((Integer) resultSet.getObject("added_lines"));
    fact.setDeletedLines((Integer) resultSet.getObject("deleted_lines"));
    fact.setCodeSpecificationCount(
        (Integer) resultSet.getObject("code_specification_count"));
    fact.setCodeLogicSpecificationCount(
        (Integer) resultSet.getObject("code_logic_specification_count"));
    fact.setPerformanceSpecificationCount(
        (Integer) resultSet.getObject("performance_specification_count"));
    fact.setDesignSpecificationCount(
        (Integer) resultSet.getObject("design_specification_count"));
    fact.setOtherSpecificationCount(
        (Integer) resultSet.getObject("other_specification_count"));
    fact.setReviewSpeedLocPerHour(
        defaultInteger(
            (Integer) resultSet.getObject("review_speed_loc_per_hour"),
            reviewSpeedLocPerHour(fact)));
    fact.setReviewSpeedKlocPerHour(
        defaultBigDecimal(
            (BigDecimal) resultSet.getObject("review_speed_kloc_per_hour"),
            reviewSpeedKlocPerHour(fact)));
    fact.setReviewDefectDensityPerKloc(
        defaultBigDecimal(
            (BigDecimal) resultSet.getObject("review_defect_density_per_kloc"),
            reviewDefectDensityPerKloc(fact)));
    fact.setReviewEfficiencyPerHour(
        defaultBigDecimal(
            (BigDecimal) resultSet.getObject("review_efficiency_per_hour"),
            reviewEfficiencyPerHour(fact)));
    fact.setCommitCount((Integer) resultSet.getObject("commit_count"));
    fact.setCommitRate(
        defaultInteger(
            (Integer) resultSet.getObject("commit_rate"), commitRate(fact)));
    fact.setFunctionName(defaultText(resultSet.getString("function_name")));
    fact.setClangAddedLineCount(
        (Integer) resultSet.getObject("clang_added_line_count"));
    fact.setDeleted(false);
    return fact;
  }

  MergeRequestFact mapSearchIndexFact(ResultSet resultSet, String sourceInstance)
      throws SQLException {
    MergeRequestFact fact = new MergeRequestFact();
    fact.setSourceSystem(
        defaultText(resultSet.getString("source_system"), DEFAULT_SOURCE_SYSTEM));
    fact.setSourceInstance(defaultText(resultSet.getString("source_instance"), sourceInstance));
    fact.setProjectId(resultSet.getLong("project_id"));
    fact.setMergeRequestId(resultSet.getLong("merge_request_id"));
    fact.setTitle(defaultText(resultSet.getString("title")));
    fact.setAuthorName(defaultText(resultSet.getString("author_name")));
    fact.setOwnerName(defaultText(resultSet.getString("owner_name")));
    fact.setProjectName(defaultText(resultSet.getString("project_name")));
    fact.setRepositoryName(defaultText(resultSet.getString("repository_name")));
    fact.setModuleName(defaultText(resultSet.getString("module_name")));
    fact.setTargetBranch(defaultText(resultSet.getString("target_branch")));
    fact.setMergeUserName(defaultText(resultSet.getString("merge_user_name")));
    return fact;
  }

  MergeRequestCommitFact mapCommit(ResultSet resultSet, String sourceInstance)
      throws SQLException {
    return new MergeRequestCommitFact(
        DEFAULT_SOURCE_SYSTEM,
        sourceInstance,
        resultSet.getLong("project_id"),
        resultSet.getLong("merge_request_id"),
        nullableLong(resultSet, "merge_request_iid"),
        resultSet.getString("commit_sha"),
        toLocalDateTime(resultSet.getTimestamp("committed_at_source")));
  }

  private Integer reviewSpeedLocPerHour(MergeRequestFact fact) {
    if (fact.getAddedLines() == null
        || fact.getReviewDurationMinutes() == null
        || fact.getReviewDurationMinutes() <= 0) {
      return null;
    }
    return (int)
        Math.round(fact.getAddedLines() * 60.0 / fact.getReviewDurationMinutes());
  }

  private BigDecimal reviewSpeedKlocPerHour(MergeRequestFact fact) {
    Integer locPerHour = fact.getReviewSpeedLocPerHour();
    return locPerHour == null
        ? null
        : BigDecimal.valueOf(locPerHour / 1000.0).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal reviewDefectDensityPerKloc(MergeRequestFact fact) {
    if (fact.getDefectCount() == null
        || fact.getAddedLines() == null
        || fact.getAddedLines() <= 0) {
      return null;
    }
    return BigDecimal.valueOf(fact.getDefectCount() * 1000.0 / fact.getAddedLines())
        .setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal reviewEfficiencyPerHour(MergeRequestFact fact) {
    if (fact.getDefectCount() == null
        || fact.getReviewDurationMinutes() == null
        || fact.getReviewDurationMinutes() <= 0) {
      return null;
    }
    return BigDecimal.valueOf(
            fact.getDefectCount() * 60.0 / fact.getReviewDurationMinutes())
        .setScale(2, RoundingMode.HALF_UP);
  }

  private Integer commitRate(MergeRequestFact fact) {
    if (fact.getAddedLines() == null
        || fact.getCommitCount() == null
        || fact.getAddedLines() <= 0
        || fact.getCommitCount() <= 0) {
      return null;
    }
    return fact.getAddedLines() / fact.getCommitCount();
  }

  private Integer defaultInteger(Integer value, Integer fallback) {
    return value == null ? fallback : value;
  }

  private BigDecimal defaultBigDecimal(BigDecimal value, BigDecimal fallback) {
    return value == null ? fallback : value;
  }
}
