package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.port.BiCodingSourcePort;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import com.data.collection.platform.common.exception.BizException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 按平台请求级读源上下文读取 BI 编码页基础度量。 */
public final class BiPlatformCodingSourceAdapter implements BiCodingSourcePort {
  private static final Set<String> INVALID_REVIEWER_VALUES = Set.of(
      "没有合法评论",
      "无合法评论",
      "代码走查时间或缺陷数异常",
      "代码走查标题异常",
      "代码走查记录行数异常",
      "GitLab 接口报错",
      "--",
      "无需标注",
      "无需走查",
      "无需走查扫描",
      "未标注人员");
  private static final String FORMAL_QUERY = """
      select id,
             project_id,
             project_name,
             business_source,
             repository_name,
             merge_request_id as merge_request_key,
             author_name,
             module_name,
             merged_at_source,
             code_walkthrough_date,
             added_lines,
             review_status,
             reviewer_names,
             review_duration_minutes,
             defect_count,
             code_specification_count,
             code_logic_specification_count,
             performance_specification_count,
             design_specification_count,
             other_specification_count,
             scan_status,
             scan_bug_count,
             comment_rate,
             comment_rate_source
        from code_review_formal_records
       where coalesce(deleted, false) = false
         and target_branch = 'dev'
         and merged_at_source is not null
       order by merged_at_source asc, id asc
      """;
  private static final String COMPATIBILITY_QUERY = """
      select id,
             project_id,
             project_name,
             source_instance as business_source,
             repository_name,
             merge_request_iid as merge_request_key,
             author_name,
             module_name,
             merged_at_source,
             code_walkthrough_date,
             added_lines,
             review_status,
             reviewer_names,
             review_duration_minutes,
             defect_count,
             code_specification_count,
             code_logic_specification_count,
             performance_specification_count,
             design_specification_count,
             other_specification_count,
             scan_status,
             scan_bug_count,
             comment_rate,
             null::varchar as comment_rate_source
        from code_review_match_mode_records
       where upper(coalesce(merge_request_state, '')) = 'MERGED'
         and target_branch = 'dev'
         and merged_at_source is not null
         and coalesce(legacy_merged_time_source, merged_at_source)
             > timestamp '2024-04-01 00:00:00'
       order by merged_at_source asc, id asc
      """;

  private final JdbcTemplate jdbcTemplate;
  private final BiPlatformCodeReviewSourceContextFactory sourceContextFactory;
  private final BiProductVersionMatcher versionMatcher;
  private final BiCodingCommitFactRepository commitRepository;

  public BiPlatformCodingSourceAdapter(
      JdbcTemplate jdbcTemplate,
      BiPlatformCodeReviewSourceContextFactory sourceContextFactory,
      BiProductVersionMatcher versionMatcher,
      BiCodingCommitFactRepository commitRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.sourceContextFactory = sourceContextFactory;
    this.versionMatcher = versionMatcher;
    this.commitRepository = commitRepository;
  }

  @Override
  public BiCodingSource load(BiProductVersionScope scope, Query query) {
    if (hasText(query.repositoryId())) {
      throw new BizException("平台尚未提供代码仓库稳定 ID，不能按仓库显示名称代替 repositoryId 筛选");
    }
    // 阶段一：捕获本次查询的读取模式、事实版本和可选能力，后续所有查询使用同一上下文。
    BiPlatformCodeReviewSourceContextFactory.Context sourceContext =
        sourceContextFactory.capture();
    List<CodingRow> codeRows =
        jdbcTemplate.query(queryFor(sourceContext.readMode()), this::mapRow);
    // 阶段二：按产品版本和来源筛选平台事实，并将原始行转换成领域层可识别的稳定身份。
    List<CodingRow> scopedRows = codeRows.stream()
        .filter(row -> versionMatcher.matches(row.projectName(), scope.businessKey()))
        .filter(row -> matchesSource(row.businessSource(), query.source()))
        .toList();
    boolean mergeRequestIdsAvailable = scopedRows.stream()
        .allMatch(row -> mergeRequestIdentity(row, sourceContext.readMode()) != null);
    boolean codeScaleAvailable = scopedRows.stream().allMatch(row -> row.addedLines() != null);
    List<BiCodingSource.MergeRequestRecord> mergeRequests = scopedRows.stream()
        .map(row -> mergeRequest(row, sourceContext.readMode()))
        .toList();
    // 走查记录与合并请求共用同一兼容读源（code_review_match_mode_records / code_review_formal_records），
    // 不再维护独立的 BI 走查兼容表重复抓取链路。
    List<CodingRow> reviewRows = codeRows;
    List<CodingRow> completedReviews = reviewRows.stream()
        .filter(row -> versionMatcher.matches(row.projectName(), scope.businessKey()))
        .filter(row -> matchesSource(row.businessSource(), query.source()))
        .filter(row -> eligibleReview(row, sourceContext.readMode()))
        .toList();
    boolean reviewMetricsAvailable = !completedReviews.isEmpty()
        && completedReviews.stream().allMatch(CodingRow::reviewMetricsAvailable);
    boolean scanDataAvailable = !completedReviews.isEmpty()
        && completedReviews.stream().anyMatch(row -> hasText(row.scanStatus())
            && (row.scanBugCount() == null || row.scanBugCount() >= 0));
    boolean commentRateDataAvailable = !completedReviews.isEmpty()
        && completedReviews.stream().anyMatch(row -> row.commentRate() != null
            && row.commentRate().signum() >= 0);
    // 阶段三：只纳入已完成且具备必要指标的评审事实；扫描和注释率能力分别标记。
    List<BiCodingSource.CodeReviewRecord> codeReviews = completedReviews.stream()
        .map(row -> codeReview(row, sourceContext.readMode()))
        .toList();
    List<BiCodingSource.CommitRecord> commits = sourceContext.commitDetailsAvailable()
        ? commitRepository.load().stream()
            .filter(row -> versionMatcher.matches(row.projectName(), scope.businessKey()))
            .filter(row -> matchesSource(row.sourceInstance(), query.source()))
            .map(this::commit)
            .toList()
        : List.of();
    // 阶段四：提交前复核事实版本，确保返回给计算器的是同一版本的合成数据集。
    sourceContextFactory.verifyDataVersion(sourceContext);
    return new BiCodingSource(
        sourceContext.sourceVersion(),
        sourceContext.sourceVersion(),
        query.granularity(),
        mergeRequests,
        commits,
        codeReviews,
        mergeRequestIdsAvailable,
        codeScaleAvailable,
        sourceContext.commitDetailsAvailable(),
        reviewMetricsAvailable,
        scanDataAvailable,
        commentRateDataAvailable);
  }

  private BiCodingSource.CommitRecord commit(
      BiCodingCommitFactRepository.CommitFactRow row) {
    String commitId = row.projectId() == null || row.projectId() <= 0 || !hasText(row.commitSha())
        ? null
        : normalizeSource(row.sourceInstance()) + ":" + row.projectId() + ":" + row.commitSha();
    return new BiCodingSource.CommitRecord(commitId, row.committedOn());
  }

  private CodingRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
    Long mergeRequestKey = BiJdbcValueReader.nullableLong(rs, "merge_request_key");
    LocalDate mergedOn = rs.getTimestamp("merged_at_source").toLocalDateTime().toLocalDate();
    LocalDate reviewedOn = rs.getTimestamp("code_walkthrough_date") == null
        ? null : rs.getTimestamp("code_walkthrough_date").toLocalDateTime().toLocalDate();
    return new CodingRow(
        rs.getLong("id"),
        BiJdbcValueReader.nullableLong(rs, "project_id"),
        rs.getString("project_name"),
        rs.getString("business_source"),
        rs.getString("repository_name"),
        mergeRequestKey,
        rs.getString("author_name"),
        rs.getString("module_name"),
        mergedOn,
        reviewedOn,
        BiJdbcValueReader.nullableLong(rs, "added_lines"),
        rs.getString("review_status"),
        rs.getString("reviewer_names"),
        rs.getBigDecimal("review_duration_minutes"),
        BiJdbcValueReader.nullableLong(rs, "defect_count"),
        BiJdbcValueReader.nullableLong(rs, "code_specification_count"),
        BiJdbcValueReader.nullableLong(rs, "code_logic_specification_count"),
        BiJdbcValueReader.nullableLong(rs, "performance_specification_count"),
        BiJdbcValueReader.nullableLong(rs, "design_specification_count"),
        BiJdbcValueReader.nullableLong(rs, "other_specification_count"),
        rs.getString("scan_status"),
        BiJdbcValueReader.nullableLong(rs, "scan_bug_count"),
        rs.getBigDecimal("comment_rate"),
        rs.getString("comment_rate_source"));
  }

  private BiCodingSource.MergeRequestRecord mergeRequest(
      CodingRow row,
      BiPlatformCodeReviewSourceContextFactory.ReadMode readMode) {
    return new BiCodingSource.MergeRequestRecord(
        mergeRequestIdentity(row, readMode),
        row.mergedOn(),
        null,
        row.repositoryName(),
        BiSourceDimension.fromNullable(row.authorName(), "未标注作者"),
        BiSourceDimension.fromNullable(row.moduleName(), "未标注模块"),
        row.addedLines());
  }

  private BiCodingSource.CodeReviewRecord codeReview(
      CodingRow row,
      BiPlatformCodeReviewSourceContextFactory.ReadMode readMode) {
    return new BiCodingSource.CodeReviewRecord(
        row.id(),
        mergeRequestIdentity(row, readMode),
        row.reviewedOn(),
        BiSourceDimension.fromNullable(row.moduleName(), "未标注模块"),
        row.addedLines(),
        row.reviewDurationMinutes(),
        row.defectCount(),
        row.codeSpecificationCount(),
        row.codeLogicSpecificationCount(),
        row.performanceSpecificationCount(),
        row.designSpecificationCount(),
        row.otherSpecificationCount(),
        row.scanStatus(),
        row.scanBugCount(),
        row.commentRate(),
        row.commentRateSource());
  }

  private BiCodingSource.MergeRequestIdentity mergeRequestIdentity(
      CodingRow row,
      BiPlatformCodeReviewSourceContextFactory.ReadMode readMode) {
    if (row.mergeRequestKey() == null || row.mergeRequestKey() <= 0) {
      return null;
    }
    if (readMode == BiPlatformCodeReviewSourceContextFactory.ReadMode.COMPATIBILITY) {
      return hasText(row.businessSource())
          ? new BiCodingSource.CompatibilityMergeRequestIdentity(
              row.businessSource(), row.mergeRequestKey())
          : null;
    }
    return row.projectId() != null && row.projectId() > 0
        ? new BiCodingSource.FormalMergeRequestIdentity(row.projectId(), row.mergeRequestKey())
        : null;
  }

  private boolean eligibleReview(
      CodingRow row,
      BiPlatformCodeReviewSourceContextFactory.ReadMode readMode) {
    if (readMode == BiPlatformCodeReviewSourceContextFactory.ReadMode.FORMAL) {
      return "COMPLETED".equalsIgnoreCase(row.reviewStatus());
    }
    String reviewerNames = row.reviewerNames() == null ? null : row.reviewerNames().trim();
    return hasText(reviewerNames) && !INVALID_REVIEWER_VALUES.contains(reviewerNames);
  }

  private boolean matchesSource(String businessSource, Source source) {
    if (source == null || source == Source.ALL) {
      return true;
    }
    String normalized = businessSource == null ? "" : businessSource.trim().toUpperCase(Locale.ROOT);
    if (source == Source.CC && "DEFAULT".equals(normalized)) {
      return true;
    }
    return normalized.equals(source.name());
  }

  private String normalizeSource(String source) {
    return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
  }

  private String queryFor(BiPlatformCodeReviewSourceContextFactory.ReadMode readMode) {
    return readMode == BiPlatformCodeReviewSourceContextFactory.ReadMode.COMPATIBILITY
        ? COMPATIBILITY_QUERY
        : FORMAL_QUERY;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private record CodingRow(
      long id,
      Long projectId,
      String projectName,
      String businessSource,
      String repositoryName,
      Long mergeRequestKey,
      String authorName,
      String moduleName,
      LocalDate mergedOn,
      LocalDate reviewedOn,
      Long addedLines,
      String reviewStatus,
      String reviewerNames,
      BigDecimal reviewDurationMinutes,
      Long defectCount,
      Long codeSpecificationCount,
      Long codeLogicSpecificationCount,
      Long performanceSpecificationCount,
      Long designSpecificationCount,
      Long otherSpecificationCount,
      String scanStatus,
      Long scanBugCount,
      BigDecimal commentRate,
      String commentRateSource) {
    private boolean reviewMetricsAvailable() {
      return addedLines != null
          && addedLines >= 0
          && reviewDurationMinutes != null
          && reviewDurationMinutes.signum() >= 0
          && defectCount != null
          && defectCount >= 0;
    }
  }
}
