package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.BiCodingCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCauseClassifier;
import com.data.collection.platform.bi.domain.BiSystemTestDelayCauseClassifier;
import com.data.collection.platform.bi.domain.port.BiCodingSourcePort;
import com.data.collection.platform.bi.domain.port.BiReviewSourcePort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BiPlatformSourceAdapterTest {
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final PageRecordSnapshotService snapshotService = mock(PageRecordSnapshotService.class);
  private final BooleanSupplier compatibilityModeEnabled = mock(BooleanSupplier.class);
  private final BiCodingCommitFactRepository commitRepository =
      mock(BiCodingCommitFactRepository.class);

  @Test
  void codingAdapterUsesFormalSourceAndMapsSharedFieldsWhenCompatibilityModeIsDisabled()
      throws Exception {
    ResultSet rs = codingRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(false);
    var adapter = new BiPlatformCodingSourceAdapter(
        jdbcTemplate,
        new BiPlatformCodeReviewSourceContextFactory(
            compatibilityModeEnabled,
            snapshotService,
            () -> "review-v1",
            () -> true,
            () -> "commits-v1"),
        new BiProductVersionMatcher(),
        commitRepository);

    var source = adapter.load(scope(), new BiCodingSourcePort.Query(
        com.data.collection.platform.bi.domain.source.BiCodingSource.Granularity.DAY,
        BiCodingSourcePort.Source.ALL,
        null));

    assertThat(source.mergeRequests()).singleElement().satisfies(record -> {
      assertThat(record.author().sourceValue()).isEqualTo("张三");
      assertThat(record.module().sourceValue()).isEqualTo("草图");
      assertThat(record.mergeRequestIdentity())
          .isEqualTo(new BiCodingSource.FormalMergeRequestIdentity(1001L, 101L));
    });
    assertThat(source.codeReviews()).singleElement().satisfies(record ->
        assertThat(record.module().displayName()).isEqualTo("草图"));
    assertThat(source.sourceVersion())
        .isEqualTo("formal:coding-v1|review:formal|commits:commits-v1");
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains("from code_review_formal_records")
        .doesNotContain("from code_review_match_mode_records");
    verify(compatibilityModeEnabled, times(1)).getAsBoolean();
  }

  @Test
  void codingAdapterUsesOnlyCompatibilitySourceForTheWholeRequest() throws Exception {
    ResultSet rs = codingRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(true, false);
    var adapter = new BiPlatformCodingSourceAdapter(
        jdbcTemplate,
        new BiPlatformCodeReviewSourceContextFactory(
            compatibilityModeEnabled,
            snapshotService,
            () -> "review-v1",
            () -> true,
            () -> "commits-v1"),
        new BiProductVersionMatcher(),
        commitRepository);

    var source = adapter.load(scope(), new BiCodingSourcePort.Query(
        com.data.collection.platform.bi.domain.source.BiCodingSource.Granularity.DAY,
        BiCodingSourcePort.Source.ALL,
        null));

    assertThat(source.sourceVersion())
        .isEqualTo("compatibility:coding-v1|review:review-v1|commits:commits-v1");
    assertThat(source.mergeRequests()).singleElement().satisfies(record ->
        assertThat(record.mergeRequestIdentity()).isEqualTo(
            new BiCodingSource.CompatibilityMergeRequestIdentity("cc", 101L)));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains("from code_review_match_mode_records")
        .doesNotContain("from bi_code_review_compatibility_records")
        .doesNotContain("from code_review_formal_records");
    verify(compatibilityModeEnabled, times(1)).getAsBoolean();
  }

  @Test
  void compatibilityCodingSourceMapsMergedLegalReviewAndOptionalCommentRateSource()
      throws Exception {
    ResultSet rs = codingRow();
    when(rs.getString("review_status")).thenReturn("MERGED");
    when(rs.getString("reviewer_names")).thenReturn("王审查");
    when(rs.getString("comment_rate_source")).thenReturn(null);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(true);
    var adapter = codingAdapter();

    BiCodingSource source = adapter.load(scope(), codingQuery());

    assertThat(source.codeReviews()).hasSize(1);
    assertThat(source.reviewMetricsAvailable()).isTrue();
    assertThat(source.scanDataAvailable()).isTrue();
    assertThat(source.commentRateDataAvailable()).isTrue();
  }

  @Test
  void compatibilityCodingSourceExcludesLegacyInvalidReviewerPlaceholders()
      throws Exception {
    ResultSet rs = codingRow();
    when(rs.getString("review_status")).thenReturn("MERGED");
    when(rs.getString("reviewer_names")).thenReturn("没有合法评论");
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(true);

    BiCodingSource source = codingAdapter().load(scope(), codingQuery());

    assertThat(source.mergeRequests()).hasSize(1);
    assertThat(source.codeReviews()).isEmpty();
    assertThat(source.reviewMetricsAvailable()).isFalse();
  }

  @Test
  void compatibilityCodingSourceKeepsSameIidFromDifferentInstancesDistinct()
      throws Exception {
    ResultSet cc = codingRow();
    when(cc.getString("business_source")).thenReturn("cc");
    when(cc.getString("repository_name")).thenReturn("CrownCAD");
    when(cc.getObject("added_lines")).thenReturn(500L);
    ResultSet dgm = codingRow();
    when(dgm.getString("business_source")).thenReturn("dgm");
    when(dgm.getString("repository_name")).thenReturn("DGM");
    when(dgm.getString("author_name")).thenReturn("李四");
    when(dgm.getObject("added_lines")).thenReturn(600L);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(
            mapper(invocation.getArgument(1)).mapRow(cc, 0),
            mapper(invocation.getArgument(1)).mapRow(dgm, 1)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(true);

    var response = new BiCodingCalculator().calculate(
        codingAdapter().load(scope(), codingQuery()));

    assertThat(response.data().summary().mergeRequestCount()).isEqualTo(2L);
    assertThat(response.data().summary().addedLines()).isEqualTo(1100L);
    assertThat(response.data().contributors()).hasSize(2);
  }

  @Test
  void formalCodingSourceKeepsSameMergeRequestIdFromDifferentProjectsDistinct()
      throws Exception {
    ResultSet projectA = codingRow();
    when(projectA.getObject("project_id")).thenReturn(1001L);
    when(projectA.getString("repository_name")).thenReturn("repository-a");
    when(projectA.getObject("added_lines")).thenReturn(500L);
    ResultSet projectB = codingRow();
    when(projectB.getObject("project_id")).thenReturn(1002L);
    when(projectB.getString("repository_name")).thenReturn("repository-b");
    when(projectB.getString("author_name")).thenReturn("李四");
    when(projectB.getObject("added_lines")).thenReturn(600L);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(
            mapper(invocation.getArgument(1)).mapRow(projectA, 0),
            mapper(invocation.getArgument(1)).mapRow(projectB, 1)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(false);

    var response = new BiCodingCalculator().calculate(
        codingAdapter().load(scope(), codingQuery()));

    assertThat(response.data().summary().mergeRequestCount()).isEqualTo(2L);
    assertThat(response.data().summary().addedLines()).isEqualTo(1100L);
  }

  @Test
  void currentCodingSourceVersionIncludesThePlatformReadMode() {
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(true, false);
    var sourceContextFactory = new BiPlatformCodeReviewSourceContextFactory(
        compatibilityModeEnabled,
        snapshotService,
        () -> "review-v1",
        () -> true,
        () -> "commits-v1");
    var adapter = new BiPlatformCurrentSourceVersionAdapter(
        snapshotService, sourceContextFactory, mock(BiCatMirrorRepository.class));

    assertThat(adapter.current("coding", scope()))
        .isEqualTo("compatibility:coding-v1|review:review-v1|commits:commits-v1");
    assertThat(adapter.current("coding", scope()))
        .isEqualTo("formal:coding-v1|review:formal|commits:commits-v1");
  }

  @Test
  void codingAdapterMarksCommitSectionsIncompleteWhenCommitSourceIsNotSelected()
      throws Exception {
    ResultSet rs = codingRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    when(compatibilityModeEnabled.getAsBoolean()).thenReturn(false);
    var adapter = new BiPlatformCodingSourceAdapter(
        jdbcTemplate,
        new BiPlatformCodeReviewSourceContextFactory(
            compatibilityModeEnabled,
            snapshotService,
            () -> "review-v1",
            () -> false,
            commitRepository::sourceVersion),
        new BiProductVersionMatcher(),
        commitRepository);

    BiCodingSource source = adapter.load(scope(), codingQuery());

    assertThat(source.commitDetailsAvailable()).isFalse();
    assertThat(source.commits()).isEmpty();
    assertThat(source.sourceVersion()).endsWith("|commits:unavailable");
    verifyNoInteractions(commitRepository);
  }

  @Test
  void reviewAdapterMapsModuleNameToSourceDimension() throws Exception {
    ResultSet rs = reviewRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.reviewDataSourceVersion()).thenReturn("review-v1");
    var adapter = new BiPlatformReviewSourceAdapter(
        jdbcTemplate, snapshotService, new BiProductVersionMatcher());

    var source = adapter.load(scope(), BiReviewSourcePort.ReviewStage.REQUIREMENTS);

    assertThat(source.records()).singleElement().satisfies(record -> {
      assertThat(record.module().sourceValue()).isEqualTo("装配");
      assertThat(record.module().identified()).isTrue();
    });
  }

  @Test
  void systemTestAdapterMapsPhysicalModuleCauseDelayAndAssigneeFields() throws Exception {
    ResultSet rs = systemTestRow();
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(invocation -> List.of(mapper(invocation.getArgument(1)).mapRow(rs, 0)));
    when(snapshotService.issueFactSourceVersion(anyString(), any(Long.class), any(), anyString()))
        .thenReturn("issue-v1");
    var adapter = new BiPlatformSystemTestSourceAdapter(
        jdbcTemplate,
        snapshotService,
        new BiSystemTestCauseClassifier(),
        new BiSystemTestDelayCauseClassifier());

    var source = adapter.load(scope());

    assertThat(source.issues()).singleElement().satisfies(issue -> {
      assertThat(issue.modules()).extracting("displayName").containsExactly("草图", "装配");
      assertThat(issue.assignee().displayName()).isEqualTo("李四");
      assertThat(issue.causes()).extracting("subcategoryName").containsExactly("功能设计遗漏");
      assertThat(issue.delayed()).isTrue();
      assertThat(issue.delayCauses()).extracting("displayName")
          .containsExactly("资源卡点", "算法问题");
    });
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
    assertThat(sql.getValue())
        .contains(
            "module_names",
            "assignee_name",
            "reason_category",
            "label_names",
            "delay_issue",
            "delay_cause")
        .doesNotContain("fix_user")
        .contains("category, '') not like '%建议%'");
  }

  @SuppressWarnings("unchecked")
  private <T> RowMapper<T> mapper(Object value) {
    return (RowMapper<T>) value;
  }

  private BiProductVersionScope scope() {
    return new BiProductVersionScope(
        11L,
        278L,
        "CC2026R3",
        "CC2026R3",
        1,
        List.of(new BiProductVersionScope.RoundScope("round-1", "CC2026R3", "第一轮", 1)));
  }

  private BiPlatformCodingSourceAdapter codingAdapter() {
    return new BiPlatformCodingSourceAdapter(
        jdbcTemplate,
        new BiPlatformCodeReviewSourceContextFactory(
            compatibilityModeEnabled,
            snapshotService,
            () -> "review-v1",
            () -> true,
            () -> "commits-v1"),
        new BiProductVersionMatcher(),
        commitRepository);
  }

  private BiCodingSourcePort.Query codingQuery() {
    return new BiCodingSourcePort.Query(
        BiCodingSource.Granularity.DAY,
        BiCodingSourcePort.Source.ALL,
        null);
  }

  private ResultSet codingRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(1L);
    when(rs.getString("project_name")).thenReturn("CC2026R3");
    when(rs.getString("business_source")).thenReturn("CC");
    when(rs.getObject("project_id")).thenReturn(1001L);
    when(rs.getString("repository_name")).thenReturn("crowncad");
    when(rs.getObject("merge_request_key")).thenReturn(101L);
    when(rs.getString("author_name")).thenReturn(" 张三 ");
    when(rs.getString("module_name")).thenReturn(" 草图 ");
    when(rs.getTimestamp("merged_at_source"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 1, 12, 0)));
    when(rs.getTimestamp("code_walkthrough_date"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 2, 12, 0)));
    when(rs.getObject("added_lines")).thenReturn(500L);
    when(rs.getString("review_status")).thenReturn("COMPLETED");
    when(rs.getString("reviewer_names")).thenReturn("王审查");
    when(rs.getBigDecimal("review_duration_minutes")).thenReturn(new BigDecimal("30"));
    when(rs.getObject("defect_count")).thenReturn(2L);
    when(rs.getObject("code_specification_count")).thenReturn(1L);
    when(rs.getObject("code_logic_specification_count")).thenReturn(1L);
    when(rs.getObject("performance_specification_count")).thenReturn(0L);
    when(rs.getObject("design_specification_count")).thenReturn(0L);
    when(rs.getObject("other_specification_count")).thenReturn(0L);
    when(rs.getString("scan_status")).thenReturn("SUCCESS_WITH_ISSUES");
    when(rs.getObject("scan_bug_count")).thenReturn(2L);
    when(rs.getBigDecimal("comment_rate")).thenReturn(new BigDecimal("12.50"));
    when(rs.getString("comment_rate_source")).thenReturn("upstream");
    return rs;
  }

  private ResultSet reviewRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(1L);
    when(rs.getString("project_name")).thenReturn("CC2026R3");
    when(rs.getString("module_name")).thenReturn(" 装配 ");
    when(rs.getObject("review_date", java.time.LocalDate.class))
        .thenReturn(java.time.LocalDate.of(2026, 8, 1));
    when(rs.getObject("review_scale_pages")).thenReturn(8L);
    when(rs.getBigDecimal("independent_review_workload")).thenReturn(new BigDecimal("2"));
    when(rs.getLong("effective_problem_count")).thenReturn(2L);
    when(rs.getLong("document_specification_count")).thenReturn(1L);
    when(rs.getLong("integrity_count")).thenReturn(1L);
    when(rs.getLong("functionality_count")).thenReturn(0L);
    when(rs.getLong("feasibility_count")).thenReturn(0L);
    return rs;
  }

  private ResultSet systemTestRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("issue_id")).thenReturn(1L);
    when(rs.getString("testing_phase")).thenReturn("CC2026R3");
    when(rs.getString("severity_level")).thenReturn("LEVEL2");
    when(rs.getString("priority_level")).thenReturn("P1");
    when(rs.getBoolean("is_fixed")).thenReturn(false);
    when(rs.getString("module_names")).thenReturn("草图, 装配, 草图");
    when(rs.getString("assignee_name")).thenReturn("李四");
    when(rs.getString("reason_category")).thenReturn("功能设计遗漏");
    when(rs.getString("label_names")).thenReturn("普通标签");
    when(rs.getBoolean("delay_issue")).thenReturn(true);
    when(rs.getString("delay_cause")).thenReturn("资源卡点&算法问题");
    when(rs.getString("delay_reason")).thenReturn(null);
    return rs;
  }
}
