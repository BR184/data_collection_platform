package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QualityBoardOtherQueryServiceTest {

  @Test
  void releaseLeakageAggregatesAllEnabledProjectsInOneIssueQuery() {
    var jdbc = new CapturingJdbcTemplate(true);
    var service = service(jdbc);

    var rows = service.load(
        QualityBoardOtherTopic.RELEASE_LEAKAGE_RATE,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("CC2026R4", "CC2026R3");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsExactly(2L, 2L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::denominator)
        .containsExactly(4L, 10L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsExactly(50D, 20D);
    assertThat(jdbc.queryCalls).hasSize(1);
    assertThat(jdbc.queryCalls.getFirst().sql())
        .contains("with phase_scope(project_name, testing_phase) as")
        .contains("left join issue_fact issue")
        .contains("exists")
        .contains("issue.testing_phase like '%' || phase.testing_phase || '%'")
        .contains("coalesce(issue.bug_status, '') not like '%已拒绝%'")
        .contains("lower(coalesce(issue.issue_state, '')) in ('open', 'opened')")
        .contains("group by scope.project_name")
        .doesNotContain("功能屏蔽")
        .doesNotContain("申请否决")
        .doesNotContain("需求如此");
    assertThat(jdbc.queryCalls.getFirst().args()).containsExactly(
        "CC2026R3", "CC2026R3第一轮系统测试",
        "CC2026R3", "CC2026R3回归测试",
        "CC2026R4", "CC2026R4系统测试",
        9L);
    assertThat(jdbc.tableExistsChecks).isZero();
  }

  @Test
  void developmentLeakageUsesOnlySystemTestOpenAndTotalCounts() {
    var jdbc = new CapturingJdbcTemplate(true);
    var service = service(jdbc);

    var rows = service.load(
        QualityBoardOtherTopic.DEVELOPMENT_LEAKAGE_RATE,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("CC2026R4", "CC2026R3");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsExactly(2L, 2L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::denominator)
        .containsExactly(4L, 10L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsExactly(50D, 20D);
    assertThat(jdbc.queryCalls).hasSize(1);
    assertThat(jdbc.queryCalls.get(0).sql()).contains("left join issue_fact issue");
    assertThat(jdbc.queryCalls.get(0).sql()).doesNotContain("integration_test_fact");
    assertThat(jdbc.tableExistsChecks).isZero();
  }

  @Test
  void functionDensityUsesContainsMembershipAndKeepsZeroDefectFunctions() {
    var jdbc = new CapturingJdbcTemplate(true);
    QualityBoardCodeReviewReadSupport codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    when(codeReview.reviewedAddedLinesByFunction("CC2026R3"))
        .thenReturn(Map.of("装配", 1000L, "工程图", 500L));
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver(), codeReview);

    var rows = service.load(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("装配", "工程图");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsExactly(2L, 0L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsExactly(0.2D, 0D);
    assertThat(jdbc.queryCalls).hasSize(1);
    assertThat(jdbc.queryCalls.getFirst().sql())
        .contains("testing_phase like ? or testing_phase like ?")
        .doesNotContain("testing_phase in (");
    assertThat(jdbc.queryCalls.getFirst().args())
        .contains("%CC2026R3第一轮系统测试%", "%CC2026R3回归测试%");
  }

  @Test
  void qualityRankingAveragesChildPhaseRatiosAndKeepsConfiguredZeroMember() {
    var jdbc = new CapturingJdbcTemplate(true);
    QualityBoardCodeReviewReadSupport codeReview = mock(QualityBoardCodeReviewReadSupport.class);
    when(codeReview.addedLinesByAuthorAcrossAllProjects())
        .thenReturn(Map.of("张金花", 1000L, "刘敏", 2000L));
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver(), codeReview);

    var rows = service.load(
        QualityBoardOtherTopic.QUALITY_RANKING,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("张金花", "刘敏");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsExactly(4L, 0L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::denominator)
        .containsExactly(1000L, 2000L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsExactly(2D, 0D);
    assertThat(jdbc.queryCalls).anySatisfy(call -> assertThat(call.sql())
        .contains("from quality_board_member_scopes")
        .contains("topic_key = 'QUALITY_RANKING'"));
  }

  private QualityBoardOtherQueryService service(CapturingJdbcTemplate jdbc) {
    return new QualityBoardOtherQueryService(
        jdbc, phaseResolver(), mock(QualityBoardCodeReviewReadSupport.class));
  }

  private SystemTestPhaseScopeResolver phaseResolver() {
    var resolver = mock(SystemTestPhaseScopeResolver.class);
    when(resolver.listEnabledParentNames(9L))
        .thenReturn(List.of("CC2026R3", "CC2026R4"));
    when(resolver.defaultParentName(9L)).thenReturn("CC2026R3");
    when(resolver.resolvePhases(9L, "CC2026R3"))
        .thenReturn(List.of("CC2026R3第一轮系统测试", "CC2026R3回归测试"));
    when(resolver.resolvePhases(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4系统测试"));
    return resolver;
  }

  private static final class CapturingJdbcTemplate extends JdbcTemplate {
    private final boolean integrationTableExists;
    private final List<QueryCall> queryCalls = new ArrayList<>();
    private int tableExistsChecks;

    private CapturingJdbcTemplate(boolean integrationTableExists) {
      this.integrationTableExists = integrationTableExists;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      queryCalls.add(new QueryCall(sql, List.of(args)));
      if (sql.contains("quality_board_member_scopes")) {
        return List.of(
            mapMember(rowMapper, "刘敏", 10),
            mapMember(rowMapper, "张金花", 20));
      }
      if (sql.contains("group by btrim(function_name)")) {
        return List.of(mapItem(rowMapper, "装配", 2L));
      }
      if (sql.contains("group by btrim(fix_user), testing_phase")) {
        return List.of(
            mapMemberPhase(rowMapper, "张金花", "CC2026R3第一轮系统测试", 4L),
            mapMemberPhase(rowMapper, "张金花", "CC2026R3回归测试", 0L));
      }
      return List.of(
          mapProject(rowMapper, "CC2026R3", 10L, 2L),
          mapProject(rowMapper, "CC2026R4", 4L, 2L));
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
      return query(sql, rowMapper, new Object[0]);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      if (requiredType == Boolean.class && sql.contains("to_regclass")) {
        tableExistsChecks++;
        return requiredType.cast(integrationTableExists);
      }
      throw new AssertionError("Unexpected scalar query: " + sql);
    }

    private <T> T mapProject(
        RowMapper<T> rowMapper,
        String projectName,
        long totalCount,
        long openCount) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getString("project_name")).thenReturn(projectName);
        when(resultSet.getLong("total_count")).thenReturn(totalCount);
        when(resultSet.getLong("open_count")).thenReturn(openCount);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }

    private <T> T mapItem(RowMapper<T> rowMapper, String itemName, long count) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getString("item_name")).thenReturn(itemName);
        when(resultSet.getLong("numerator")).thenReturn(count);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }

    private <T> T mapMember(RowMapper<T> rowMapper, String memberName, int displayOrder) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getString("member_name")).thenReturn(memberName);
        when(resultSet.getInt("display_order")).thenReturn(displayOrder);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }

    private <T> T mapMemberPhase(
        RowMapper<T> rowMapper, String memberName, String testingPhase, long count) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getString("member_name")).thenReturn(memberName);
        when(resultSet.getString("testing_phase")).thenReturn(testingPhase);
        when(resultSet.getLong("defect_count")).thenReturn(count);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }
  }

  private record QueryCall(String sql, List<Object> args) {}
}
