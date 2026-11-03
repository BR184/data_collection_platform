package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 组合入口的评审去重与集成测试来源展开。 */
class QualityBoardRdServiceCombinedScopeTest {
  private static final String COMBINED_KEY = "cc-2027-r1-r2";
  private static final String COMBINED = "CC2027R1&R2";
  private static final String DEMAND = "需求说明书评审";

  @Test
  void theSameReviewMatchedBySeveralMembersOrAliasesIsCountedOnce() {
    ReviewDataRecordReadRepository formal = mock(ReviewDataRecordReadRepository.class);
    ReviewDataMatchModeRecordRepository match = mock(ReviewDataMatchModeRecordRepository.class);
    when(formal.loadRecords(null, "CC2027R1", null, null, null, null, null, null))
        .thenReturn(List.of(review(1L, "CC2027R1", DEMAND, 10, 10)));
    when(formal.loadRecords(null, "CC2027R2", null, null, null, null, null, null))
        .thenReturn(List.of(review(1L, "CC2027R2", DEMAND, 10, 10)));
    when(formal.loadRecords(null, "2027R1&R2", null, null, null, null, null, null))
        .thenReturn(List.of(review(1L, "2027R1&R2", DEMAND, 10, 10)));
    when(match.loadRecords())
        .thenReturn(List.of(review(-5L, "2027R1&R2", DEMAND, 5, 10)));
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    QualityBoardCodeReviewReadSupport codeReview =
        mock(QualityBoardCodeReviewReadSupport.class);
    QualityBoardRdService service = new QualityBoardRdService(
        jdbc,
        formal,
        match,
        codeReview,
        DashboardVersionScopeTestSupport.service(
            List.of(r1Group(), r2Group()),
            List.of(DashboardVersionScopeTestSupport.scope(
                "cc-2027-r1-r2",
                COMBINED,
                "CC2027R1",
                List.of("CC2027R1", "CC2027R2"),
                List.of("2027R1&R2"),
                List.of()))));

    var overview = service.getOverview(COMBINED_KEY, CodeReviewDataReadMode.FORMAL);

    assertThat(overview.demandReviewReportDensity()).isEqualTo(0.75D);
    assertThat(overview.designReviewReportDensity()).isZero();
  }

  @Test
  void integrationSourcesExpandToMemberPhasesWithoutInventingACombinedPhase() {
    CapturingIntegrationJdbcTemplate jdbc = new CapturingIntegrationJdbcTemplate();
    QualityBoardRdService service = new QualityBoardRdService(
        jdbc,
        mock(ReviewDataRecordReadRepository.class),
        mock(ReviewDataMatchModeRecordRepository.class),
        mock(QualityBoardCodeReviewReadSupport.class),
        DashboardVersionScopeTestSupport.service(
            List.of(r1Group(), r2Group()),
            List.of(DashboardVersionScopeTestSupport.scope(
                "cc-2027-r1-r2",
                COMBINED,
                "CC2027R1",
                List.of("CC2027R1", "CC2027R2"),
                List.of(),
                List.of()))));

    var overview = service.getOverview(COMBINED_KEY, CodeReviewDataReadMode.FORMAL);

    assertThat(overview.integrationPassRate()).isEqualTo(70D);
    assertThat(jdbc.integrationPhases)
        .containsExactly("CC2027R1集成测试", "CC2027R2集成测试")
        .doesNotContain(COMBINED + "集成测试");
    assertThat(jdbc.notPassCount).isEqualTo(2L);
  }

  @Test
  void renamedNormalVersionSupportsDefaultAndStableKeySelection() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L);
    QualityBoardRdService service = new QualityBoardRdService(
        jdbc,
        mock(ReviewDataRecordReadRepository.class),
        mock(ReviewDataMatchModeRecordRepository.class),
        mock(QualityBoardCodeReviewReadSupport.class),
        DashboardVersionScopeTestSupport.service(
            DashboardVersionScopeTestSupport.group(
                11L,
                "CC2026R3",
                "CC 2026 R3 发布版",
                1,
                DashboardVersionScopeTestSupport.member(111L, "CC2026R3第一轮系统测试", 1))));

    var options = service.listProjectOptions();
    assertThat(options.defaultProjectName()).isEqualTo("CC2026R3");
    assertThat(options.options()).singleElement().satisfies(option -> {
      assertThat(option.value()).isEqualTo("CC2026R3");
      assertThat(option.label()).isEqualTo("CC 2026 R3 发布版");
    });
    assertThat(service.getOverview(null, CodeReviewDataReadMode.FORMAL).projectName())
        .isEqualTo("CC 2026 R3 发布版");
    assertThat(service.getOverview("CC2026R3", CodeReviewDataReadMode.FORMAL).projectName())
        .isEqualTo("CC 2026 R3 发布版");
  }

  private static com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup r1Group() {
    return DashboardVersionScopeTestSupport.group(
        10L,
        "CC2027R1",
        2,
        DashboardVersionScopeTestSupport.member(101L, "CC2027R1第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(102L, "CC2027R1回归测试", 2));
  }

  private static com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup r2Group() {
    return DashboardVersionScopeTestSupport.group(
        20L,
        "CC2027R2",
        3,
        DashboardVersionScopeTestSupport.member(201L, "CC2027R2第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(202L, "CC2027R2回归测试", 2));
  }

  private static ReviewDataRecordRowResponse review(
      long id, String projectName, String reviewType, int problems, int pages) {
    return new ReviewDataRecordRowResponse(
        id,
        projectName,
        "title",
        "module",
        reviewType,
        LocalDate.of(2026, 10, 1),
        "owner",
        "experts",
        pages,
        "product",
        "author",
        "version",
        problems,
        0D,
        LocalDateTime.of(2026, 10, 1, 0, 0),
        false);
  }

  private static final class CapturingIntegrationJdbcTemplate extends JdbcTemplate {
    private List<String> integrationPhases = List.of();
    private long notPassCount;

    @Override
    public <T> T query(
        String sql,
        org.springframework.jdbc.core.ResultSetExtractor<T> extractor,
        Object... args) {
      if (sql.contains("select pass_case, execute_case")) {
        integrationPhases = List.of((String) args[2], (String) args[3]);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        try {
          when(rs.next()).thenReturn(true, true, false);
          when(rs.getObject("pass_case")).thenReturn(1, 9);
          when(rs.getObject("execute_case")).thenReturn(2, 10);
          return extractor.extractData(rs);
        } catch (java.sql.SQLException error) {
          throw new AssertionError(error);
        }
      }
      throw new AssertionError("Unexpected query: " + sql);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      if (requiredType == Boolean.class && sql.contains("to_regclass")) {
        return requiredType.cast(true);
      }
      if (requiredType == Long.class && sql.contains("sum(not_pass_case)")) {
        notPassCount = 2L;
        return requiredType.cast(2L);
      }
      if (requiredType == Long.class && sql.contains("count(*)")) {
        return requiredType.cast(0L);
      }
      throw new AssertionError("Unexpected scalar query: " + sql);
    }

    @Override
    public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
      if (sql.contains("from issue_fact") && sql.contains("group by person_name")) {
        return List.of();
      }
      throw new AssertionError("Unexpected query: " + sql);
    }
  }
}
