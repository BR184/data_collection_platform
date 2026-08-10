package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.port.BiReviewSourcePort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiReviewSource;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 从统一评审可见视图读取 BI 需求和设计评审基础度量。 */
public final class BiPlatformReviewSourceAdapter implements BiReviewSourcePort {
  private final JdbcTemplate jdbcTemplate;
  private final PageRecordSnapshotService snapshotService;
  private final BiProductVersionMatcher versionMatcher;

  public BiPlatformReviewSourceAdapter(
      JdbcTemplate jdbcTemplate,
      PageRecordSnapshotService snapshotService,
      BiProductVersionMatcher versionMatcher) {
    this.jdbcTemplate = jdbcTemplate;
    this.snapshotService = snapshotService;
    this.versionMatcher = versionMatcher;
  }

  @Override
  public BiReviewSource load(BiProductVersionScope scope, ReviewStage stage) {
    // 阶段一：在查询前记录事实层版本，保证本次读取可以检测并发发布。
    String sourceVersion = snapshotService.reviewDataSourceVersion();
    List<ReviewRow> rows = jdbcTemplate.query(
        """
        select r.id,
               r.project_name,
               r.module_name,
               r.review_date,
               r.review_scale_pages,
               sum(p.workload_hours) filter (where p.review_category = '独立评审')
                 as independent_review_workload,
               count(*) filter (
                 where p.id is not null
                   and p.problem_status not in ('已拒绝', '未评审', '无问题')
                   and p.problem_category <> '无问题'
               )::bigint as effective_problem_count,
               count(*) filter (
                 where p.problem_status not in ('已拒绝', '未评审', '无问题')
                   and p.problem_category = '文档规范'
               )::bigint as document_specification_count,
               count(*) filter (
                 where p.problem_status not in ('已拒绝', '未评审', '无问题')
                   and p.problem_category = '完整性'
               )::bigint as integrity_count,
               count(*) filter (
                 where p.problem_status not in ('已拒绝', '未评审', '无问题')
                   and p.problem_category = '功能性'
               )::bigint as functionality_count,
               count(*) filter (
                 where p.problem_status not in ('已拒绝', '未评审', '无问题')
                   and p.problem_category = '可行性'
               )::bigint as feasibility_count
          from review_visible_records r
          left join review_visible_problem_items p
            on p.review_record_id = r.id
           and coalesce(p.deleted, false) = false
         where coalesce(r.deleted, false) = false
           and r.review_type = ?
         group by r.id, r.project_name, r.module_name, r.review_date, r.review_scale_pages
         order by r.review_date asc, r.id asc
        """,
        this::mapRow,
        stage.reviewType());
    // 阶段二：先按产品版本筛选，再转换为领域记录；产品版本匹配不下沉到计算器。
    List<BiReviewSource.ReviewRecord> records = rows.stream()
        .filter(row -> versionMatcher.matches(row.projectName(), scope.businessKey()))
        .map(this::toSourceRecord)
        .toList();
    // 阶段三：读取结束后复核版本，阻止一半旧数据和一半新数据进入同一页面。
    verifyStableVersion(stage.pageKey(), sourceVersion, snapshotService.reviewDataSourceVersion());
    return new BiReviewSource(sourceVersion, sourceVersion, records);
  }

  private ReviewRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
    return new ReviewRow(
        rs.getLong("id"),
        rs.getString("project_name"),
        rs.getString("module_name"),
        rs.getObject("review_date", LocalDate.class),
        BiJdbcValueReader.nullableLong(rs, "review_scale_pages"),
        rs.getBigDecimal("independent_review_workload"),
        rs.getLong("effective_problem_count"),
        rs.getLong("document_specification_count"),
        rs.getLong("integrity_count"),
        rs.getLong("functionality_count"),
        rs.getLong("feasibility_count"));
  }

  private BiReviewSource.ReviewRecord toSourceRecord(ReviewRow row) {
    return new BiReviewSource.ReviewRecord(
        row.id(),
        row.reviewDate(),
        BiSourceDimension.fromNullable(row.moduleName(), "未标注模块"),
        row.reviewScalePages(),
        row.independentReviewWorkload(),
        row.effectiveProblemCount(),
        row.documentSpecificationCount(),
        row.integrityCount(),
        row.functionalityCount(),
        row.feasibilityCount());
  }

  private void verifyStableVersion(String pageKey, String before, String after) {
    if (!before.equals(after)) {
      throw new BiSourceVersionChangedException(pageKey);
    }
  }

  private record ReviewRow(
      long id,
      String projectName,
      String moduleName,
      LocalDate reviewDate,
      Long reviewScalePages,
      BigDecimal independentReviewWorkload,
      long effectiveProblemCount,
      long documentSpecificationCount,
      long integrityCount,
      long functionalityCount,
      long feasibilityCount) {}
}
