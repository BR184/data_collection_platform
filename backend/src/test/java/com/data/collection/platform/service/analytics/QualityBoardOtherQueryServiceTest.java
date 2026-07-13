package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QualityBoardOtherQueryServiceTest {

  @Test
  void releaseLeakageAggregatesAllEnabledProjectsInOneIssueQuery() {
    var jdbc = new CapturingJdbcTemplate(true);
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver());

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
  void developmentLeakageUsesTwoBatchQueriesAndChecksIntegrationTableOnce() {
    var jdbc = new CapturingJdbcTemplate(true);
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver());

    var rows = service.load(
        QualityBoardOtherTopic.DEVELOPMENT_LEAKAGE_RATE,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("CC2026R4", "CC2026R3");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsExactly(4L, 5L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::denominator)
        .containsExactly(4L, 10L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsExactly(50D, 33.33D);
    assertThat(jdbc.queryCalls).hasSize(2);
    assertThat(jdbc.queryCalls.get(0).sql()).contains("left join issue_fact issue");
    assertThat(jdbc.queryCalls.get(1).sql())
        .contains("with integration_scope(project_name, testing_phase) as")
        .contains("left join integration_test_fact integration_fact")
        .contains("integration_fact.source_instance")
        .contains("integration_fact.project_id = ?")
        .contains("group by scope.project_name");
    assertThat(jdbc.queryCalls.get(1).args()).containsExactly(
        "CC2026R3", "CC2026R3集成测试",
        "CC2026R4", "CC2026R4集成测试",
        "default", 9L);
    assertThat(jdbc.tableExistsChecks).isEqualTo(1);
  }

  @Test
  void missingIntegrationTableKeepsAllProjectsWithoutIssuingIntegrationQuery() {
    var jdbc = new CapturingJdbcTemplate(false);
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver());

    var rows = service.load(
        QualityBoardOtherTopic.DEVELOPMENT_LEAKAGE_RATE,
        AnalyticsDashboardQueryContext.empty());

    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::name)
        .containsExactly("CC2026R3", "CC2026R4");
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::numerator)
        .containsOnly(0L);
    assertThat(rows).extracting(QualityBoardOtherQueryService.Row::value)
        .containsOnly(0D);
    assertThat(jdbc.queryCalls).hasSize(1);
    assertThat(jdbc.tableExistsChecks).isEqualTo(1);
  }

  @Test
  void codeLineDenominatorsStayInsideFormalCrownCadProject() {
    var jdbc = new CapturingJdbcTemplate(true);
    var service = new QualityBoardOtherQueryService(jdbc, phaseResolver());

    service.load(
        QualityBoardOtherTopic.FUNCTION_DEFECT_DENSITY,
        AnalyticsDashboardQueryContext.empty());

    assertThat(jdbc.queryCalls).hasSize(1);
    assertThat(jdbc.queryCalls.getFirst().sql())
        .contains("from merge_request_fact")
        .contains("and project_id = ?")
        .contains("and project_name = ?");
    assertThat(jdbc.queryCalls.getFirst().args())
        .containsExactly(
            "default",
            9L,
            "CC2026R3",
            9L,
            "CC2026R3第一轮系统测试",
            "CC2026R3回归测试");
  }

  private SystemTestPhaseScopeResolver phaseResolver() {
    var resolver = mock(SystemTestPhaseScopeResolver.class);
    when(resolver.listEnabledLegacyCrownCadParentNames())
        .thenReturn(List.of("CC2026R3", "CC2026R4"));
    when(resolver.resolveLegacyCrownCadPhases("CC2026R3"))
        .thenReturn(List.of("CC2026R3第一轮系统测试", "CC2026R3回归测试"));
    when(resolver.resolveLegacyCrownCadPhases("CC2026R4"))
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
      if (sql.contains("integration_test_fact")) {
        return List.of(
            map(rowMapper, "CC2026R3", 0L, 0L, 5L),
            map(rowMapper, "CC2026R4", 0L, 0L, 4L));
      }
      return List.of(
          map(rowMapper, "CC2026R3", 10L, 2L, 0L),
          map(rowMapper, "CC2026R4", 4L, 2L, 0L));
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      if (requiredType == Boolean.class && sql.contains("to_regclass")) {
        tableExistsChecks++;
        return requiredType.cast(integrationTableExists);
      }
      throw new AssertionError("Unexpected scalar query: " + sql);
    }

    private <T> T map(
        RowMapper<T> rowMapper,
        String projectName,
        long totalCount,
        long openCount,
        long integrationNotPass) {
      ResultSet resultSet = mock(ResultSet.class);
      try {
        when(resultSet.getString("project_name")).thenReturn(projectName);
        when(resultSet.getLong("total_count")).thenReturn(totalCount);
        when(resultSet.getLong("open_count")).thenReturn(openCount);
        when(resultSet.getLong("integration_not_pass")).thenReturn(integrationNotPass);
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }
  }

  private record QueryCall(String sql, List<Object> args) {}
}
