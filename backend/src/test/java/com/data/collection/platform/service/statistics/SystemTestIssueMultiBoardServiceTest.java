package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SystemTestIssueMultiBoardServiceTest {
  @Test
  void everyChartCarriesRegisteredRulesAndBackendOwnedDrillDownParameters() {
    SystemTestPhaseCatalogService phaseCatalog = mock(SystemTestPhaseCatalogService.class);
    SystemTestPhaseScopeResolver phaseResolver = mock(SystemTestPhaseScopeResolver.class);
    when(phaseCatalog.listParentNames(9L)).thenReturn(List.of("CC2026R3"));
    when(phaseResolver.resolvePhases(9L, "CC2026R3"))
        .thenReturn(List.of("CC2026R3系统测试"));
    SystemTestIssueMultiBoardService service = new SystemTestIssueMultiBoardService(
        new BoardJdbcTemplate(), phaseCatalog, phaseResolver, new ObjectMapper());

    var board = service.getBoard(9L, "CC2026R3");

    assertThat(board.charts()).hasSize(11);
    assertThat(board.rules()).hasSize(15);
    assertThat(board.summaryCards()).allSatisfy(card -> assertThat(card.ruleKey()).isNotBlank());
    assertThat(board.charts()).allSatisfy(chart -> {
      assertThat(chart.ruleKey()).isEqualTo(chart.key());
      assertThat(chart.detailViewKey()).isNotBlank();
      assertThat(chart.detailParams())
          .containsEntry("projectId", "9")
          .containsEntry("testingPhase", "CC2026R3");
    });
    var severityPoint = board.charts().stream()
        .filter(chart -> chart.key().equals("severity-level"))
        .findFirst()
        .orElseThrow()
        .points()
        .getFirst();
    assertThat(severityPoint.detailViewKey()).isEqualTo("system-test-issue-records");
    assertThat(severityPoint.detailParams().get("filterGroup"))
        .contains("metricSeverity")
        .contains("LEVEL1");
    var phasePoint = board.charts().stream()
        .filter(chart -> chart.key().equals("phase-severity"))
        .findFirst()
        .orElseThrow()
        .series()
        .getFirst()
        .data()
        .getFirst();
    assertThat(phasePoint.detailParams().get("filterGroup"))
        .contains("testingPhase")
        .contains("CC2026R3系统测试");
  }

  private static final class BoardJdbcTemplate extends JdbcTemplate {
    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      if (sql.contains("max(coalesce(project_name")) {
        return List.of(map(rowMapper, projectRow()));
      }
      if (sql.contains("from issue_fact")) {
        return List.of(map(rowMapper, issueRow()));
      }
      throw new AssertionError("Unexpected query: " + sql);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      if (elementType == String.class && sql.contains("from issue_fact")) {
        return List.of(elementType.cast("CrownCAD"));
      }
      throw new AssertionError("Unexpected list query: " + sql);
    }

    private <T> T map(RowMapper<T> rowMapper, ResultSet resultSet) {
      try {
        return rowMapper.mapRow(resultSet, 0);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }

    private ResultSet projectRow() {
      ResultSet row = mock(ResultSet.class);
      try {
        when(row.getLong("project_id")).thenReturn(9L);
        when(row.getString("project_name")).thenReturn("CrownCAD");
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
      return row;
    }

    private ResultSet issueRow() {
      ResultSet row = mock(ResultSet.class);
      try {
        when(row.getLong("project_id")).thenReturn(9L);
        when(row.getString("project_name")).thenReturn("CrownCAD");
        when(row.getLong("issue_id")).thenReturn(1001L);
        when(row.getLong("issue_iid")).thenReturn(101L);
        when(row.getString("title")).thenReturn("修复后出现回退");
        when(row.getString("issue_state")).thenReturn("opened");
        when(row.getString("testing_phase")).thenReturn("CC2026R3系统测试");
        when(row.getString("severity_level")).thenReturn("LEVEL1");
        when(row.getString("bug_status")).thenReturn("未修复");
        when(row.getString("category")).thenReturn("");
        when(row.getString("reason_category")).thenReturn("需求问题");
        when(row.getString("delay_cause")).thenReturn("");
        when(row.getString("delay_reason")).thenReturn("");
        when(row.getString("module_names")).thenReturn("草图");
        when(row.getString("fix_user")).thenReturn("张三");
        when(row.getString("label_names")).thenReturn("新增理解偏差, 回退");
        when(row.getBoolean("is_excluded")).thenReturn(false);
        when(row.getString("exclusion_reason")).thenReturn("");
        when(row.getBoolean("is_fixed")).thenReturn(false);
        when(row.getBoolean("delay_issue")).thenReturn(false);
        when(row.getBoolean("is_regression")).thenReturn(true);
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
      return row;
    }
  }
}
