package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.io.ByteArrayInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SystemTestHorizontalComparisonExportServiceTest {
  private final RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
  private final ReviewDataMirrorOptionRepository mirrorOptionRepository =
      mock(ReviewDataMirrorOptionRepository.class);
  private final SystemTestPhaseScopeResolver phaseScopeResolver =
      mock(SystemTestPhaseScopeResolver.class);
  private final SystemTestHorizontalComparisonExportService exportService =
      new SystemTestHorizontalComparisonExportService(
          jdbcTemplate,
          mock(JsonUtils.class),
          phaseScopeResolver,
          mock(CodeReviewMatchModeSwitchService.class),
          mock(ReviewDataMatchModeRecordRepository.class),
          mirrorOptionRepository);

  @Test
  void horizontalExportLoadsAtomicModuleCatalogFromMirroredGitlabLabels() {
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of("工程图", "平台"));

    exportService.exportCsv(Map.of("projectName", "CC2026R4"));

    verify(mirrorOptionRepository).loadModuleNames();
  }

  @Test
  void codeReviewFactsNeverBecomeHorizontalExportModuleCatalog() {
    exportService.exportCsv(Map.of("projectName", "CC2026R4"));

    assertThat(jdbcTemplate.queries())
        .extracting(ModuleQuery::sql)
        .allSatisfy(sql -> assertThat(sql)
            .doesNotContain("from code_review_formal_records")
            .doesNotContain("from code_review_match_mode_records"));
  }

  @Test
  void horizontalExportIncludesHistoricalReviewSnapshotsWhenCompatibilityReadEnabled() {
    ReviewDataMatchModeRecordRepository reviewRepository =
        mock(ReviewDataMatchModeRecordRepository.class);
    when(reviewRepository.loadRecords())
        .thenReturn(
            List.of(
                new ReviewDataRecordRowResponse(
                    -1L,
                    "CC2026R4",
                    "历史需求评审",
                    "历史模块",
                    "需求说明书评审",
                    LocalDate.of(2026, 6, 1),
                    "负责人A",
                    "专家A",
                    10,
                    "需求文档",
                    "作者A",
                    "V1",
                    3,
                    0.3D,
                    LocalDateTime.of(2026, 6, 1, 10, 0),
                    false)));
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    when(switchService.isReviewDataCompatibilityReadEnabled()).thenReturn(true);
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(SystemTestPhaseScopeResolver.class),
            switchService,
            reviewRepository,
            mirrorOptionRepository);

    String csv = service.exportCsv(Map.of("projectName", "CC2026R4"));

    assertThat(csv).contains("历史模块");
  }

  @Test
  void horizontalExportExcludesHistoricalReviewSnapshotsWhenCompatibilityReadDisabled() {
    ReviewDataMatchModeRecordRepository reviewRepository =
        mock(ReviewDataMatchModeRecordRepository.class);
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    when(switchService.isReviewDataCompatibilityReadEnabled()).thenReturn(false);
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            mock(SystemTestPhaseScopeResolver.class),
            switchService,
            reviewRepository,
            mirrorOptionRepository);

    service.exportCsv(Map.of("projectName", "CC2026R4"));

    verifyNoInteractions(reviewRepository);
  }

  @Test
  void issueSummaryUsesContainsMembershipForCompoundPhaseFacts() {
    SystemTestPhaseScopeResolver phaseResolver = mock(SystemTestPhaseScopeResolver.class);
    when(phaseResolver.resolvePhases(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试"));
    SystemTestHorizontalComparisonExportService service =
        new SystemTestHorizontalComparisonExportService(
            jdbcTemplate,
            mock(JsonUtils.class),
            phaseResolver,
            mock(CodeReviewMatchModeSwitchService.class),
            mock(ReviewDataMatchModeRecordRepository.class),
            mirrorOptionRepository);

    service.exportCsv(Map.of("testingPhase", "CC2026R4"));

    assertThat(jdbcTemplate.issueQuery()).isNotNull();
    assertThat(jdbcTemplate.issueQuery().sql())
        .contains("testing_phase like ? or testing_phase like ?")
        .doesNotContain("lower(coalesce(testing_phase, '')) = ?");
    assertThat(jdbcTemplate.issueQuery().args())
        .contains("%CC2026R4第一轮系统测试%", "%CC2026R4第二轮系统测试%");
  }

  @Test
  void test_horizontal_workbook_writes_p2_and_p3_close_rates() throws Exception {
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of("模块A"));
    when(phaseScopeResolver.resolvePhases(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4第一轮系统测试"));
    jdbcTemplate.setIssueRows(
        List.of(
            issueRow(101L, "P2", true),
            issueRow(102L, "P2", false),
            issueRow(103L, "P3", true)));

    byte[] content = exportService.exportWorkbook(Map.of("testingPhase", "CC2026R4"));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheet("系统测试数据分析");
      int p2Column = findHeaderColumn(sheet, "P2缺陷关闭率(%)");
      int p3Column = findHeaderColumn(sheet, "P3缺陷关闭率(%)");
      assertThat(p2Column).isNotNegative();
      assertThat(p3Column).isEqualTo(p2Column + 3);
      assertThat(sheet.getRow(3).getCell(p2Column).getStringCellValue()).isEqualTo("50.00");
      assertThat(sheet.getRow(3).getCell(p3Column).getStringCellValue()).isEqualTo("100.00");
    }
  }

  private ResultSet issueRow(long issueIid, String priority, boolean closed) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getLong("issue_iid")).thenReturn(issueIid);
    when(row.getString("title")).thenReturn("议题" + issueIid);
    when(row.getString("issue_state")).thenReturn(closed ? "closed" : "opened");
    when(row.getTimestamp("closed_at_source"))
        .thenReturn(closed ? Timestamp.valueOf(LocalDateTime.of(2026, 7, 1, 9, 0)) : null);
    when(row.getString("module_names")).thenReturn("模块A");
    when(row.getString("severity_level")).thenReturn("LEVEL2");
    when(row.getString("priority_level")).thenReturn(priority);
    when(row.getString("bug_status")).thenReturn("处理中");
    when(row.getString("category")).thenReturn("缺陷");
    when(row.getString("exclusion_reason")).thenReturn("");
    when(row.getString("reason_category")).thenReturn("");
    when(row.getString("raw_payload")).thenReturn("");
    when(row.getString("label_names")).thenReturn("");
    return row;
  }

  private int findHeaderColumn(Sheet sheet, String header) {
    for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
      for (int columnIndex = 0; columnIndex < sheet.getRow(rowIndex).getLastCellNum(); columnIndex++) {
        if (header.equals(sheet.getRow(rowIndex).getCell(columnIndex).getStringCellValue())) {
          return columnIndex;
        }
      }
    }
    return -1;
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<ModuleQuery> queries = new java.util.ArrayList<>();
    private List<ResultSet> issueRows = List.of();
    private ModuleQuery issueQuery;

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      queries.add(new ModuleQuery(sql, List.copyOf(Arrays.asList(args))));
      return List.of();
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      if (sql.contains("from issue_fact")) {
        issueQuery = new ModuleQuery(sql, List.copyOf(Arrays.asList(args)));
        List<T> mapped = new java.util.ArrayList<>();
        for (int index = 0; index < issueRows.size(); index++) {
          try {
            mapped.add(rowMapper.mapRow(issueRows.get(index), index));
          } catch (SQLException error) {
            throw new IllegalStateException(error);
          }
        }
        return List.copyOf(mapped);
      }
      return List.of();
    }

    private void setIssueRows(List<ResultSet> rows) {
      issueRows = List.copyOf(rows);
    }

    private List<ModuleQuery> queries() {
      return List.copyOf(queries);
    }

    private ModuleQuery issueQuery() {
      return issueQuery;
    }
  }

  private record ModuleQuery(String sql, List<Object> args) {}
}
