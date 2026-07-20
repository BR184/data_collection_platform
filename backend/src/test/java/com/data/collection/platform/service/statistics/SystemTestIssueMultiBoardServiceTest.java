package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

  @Test
  void exportsLegacyWorkbookContractsForChartsThatOwnDetailOrTotalColumns() throws Exception {
    SystemTestIssueMultiBoardService service = service();

    byte[] severityWorkbook = service.exportChart(9L, "CC2026R3", "severity-level");
    assertWorkbook(
        severityWorkbook,
        List.of("统计结果", "原始数据"),
        List.of("名称", "数量"),
        LEGACY_SEVERITY_RAW_HEADERS);
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(severityWorkbook))) {
      Row rawRow = workbook.getSheet("原始数据").getRow(1);
      assertThat(rawRow.getCell(14).getStringCellValue()).isEqualTo("需求问题");
    }
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "phase-severity"),
        List.of("原始数据"),
        LEGACY_PHASE_RAW_HEADERS);
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "module-severity"),
        List.of("统计结果", "原始数据"),
        List.of("模块名称", "一级缺陷", "二级缺陷", "三级缺陷", "建议类缺陷", "合计"),
        LEGACY_MODULE_RAW_HEADERS);
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "major-cause"),
        List.of("统计结果", "原始数据"),
        List.of("名称", "数量"),
        LEGACY_SEVERITY_RAW_HEADERS);
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "cause-detail"),
        List.of("统计结果", "原始数据"),
        LEGACY_CAUSE_SUMMARY_HEADERS,
        LEGACY_CAUSE_RAW_HEADERS);
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "fix-user-severity"),
        List.of("修复人员统计"),
        LEGACY_CAUSE_SUMMARY_HEADERS);
    assertWorkbook(
        service.exportChart(9L, "CC2026R3", "delay-cause"),
        List.of("申请延期缺陷原因分析"),
        LEGACY_CAUSE_SUMMARY_HEADERS);
  }

  private SystemTestIssueMultiBoardService service() {
    SystemTestPhaseCatalogService phaseCatalog = mock(SystemTestPhaseCatalogService.class);
    SystemTestPhaseScopeResolver phaseResolver = mock(SystemTestPhaseScopeResolver.class);
    when(phaseCatalog.listParentNames(9L)).thenReturn(List.of("CC2026R3"));
    when(phaseResolver.resolvePhases(9L, "CC2026R3"))
        .thenReturn(List.of("CC2026R3系统测试"));
    return new SystemTestIssueMultiBoardService(
        new BoardJdbcTemplate(), phaseCatalog, phaseResolver, new ObjectMapper());
  }

  private void assertWorkbook(byte[] content, List<String> sheetNames, List<String>... expectedHeaders)
      throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      assertThat(workbook.sheetIterator())
          .toIterable()
          .extracting(sheet -> sheet.getSheetName())
          .containsExactlyElementsOf(sheetNames);
      assertThat(expectedHeaders).hasSameSizeAs(sheetNames);
      for (int index = 0; index < sheetNames.size(); index++) {
        assertThat(headerValues(workbook.getSheet(sheetNames.get(index)).getRow(0)))
            .containsExactlyElementsOf(expectedHeaders[index]);
      }
    }
  }

  private List<String> headerValues(Row row) {
    return IntStream.range(0, row.getLastCellNum())
        .mapToObj(index -> row.getCell(index).getStringCellValue())
        .toList();
  }

  private static final List<String> LEGACY_CAUSE_SUMMARY_HEADERS =
      List.of("缺陷原因", "一级缺陷", "二级缺陷", "三级缺陷", "需求&建议类", "共计");
  private static final List<String> LEGACY_SEVERITY_RAW_HEADERS =
      List.of(
          "议题严重程度", "议题更新时间", "议题提交时间", "模块名", "议题编号", "议题标题",
          "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "议题类别", "里程碑",
          "优先级", "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> LEGACY_PHASE_RAW_HEADERS =
      List.of(
          "阶段", "议题更新时间", "议题提交时间", "模块名", "议题编号", "议题标题", "议题提交人",
          "议题处理人", "测试阶段", "议题状态", "测试状态", "议题严重程度", "议题类别", "里程碑",
          "优先级", "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> LEGACY_MODULE_RAW_HEADERS =
      List.of(
          "模块名", "议题严重程度", "议题更新时间", "议题提交时间", "议题编号", "议题标题",
          "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "里程碑", "优先级",
          "缺陷原因", "一级缺陷原因", "二级缺陷原因", "其他原因", "延期原因", "缺陷修复人");
  private static final List<String> LEGACY_CAUSE_RAW_HEADERS =
      List.of(
          "一级缺陷原因", "二级缺陷原因", "议题更新时间", "议题提交时间", "模块名", "议题编号",
          "议题标题", "议题提交人", "议题处理人", "议题状态", "测试状态", "测试阶段", "议题严重程度",
          "议题类别", "里程碑", "优先级", "缺陷原因", "其他原因", "延期原因", "缺陷修复人");

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
