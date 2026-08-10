package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectSummaryBoardServiceTest {

  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    when(milestoneCatalogService.matches("CC2026R3", "CC2026 R3")).thenReturn(true);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);

    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(4);
              return List.of(
                  mapper.mapRow(issue(1001, "P2", true, "已知的受影响功能"), 0),
                  mapper.mapRow(issue(1002, "P3", false, "新识别的受影响功能"), 1));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
  }

  @Test
  void test_stable_milestone_key_matches_exact_fact_member() throws Exception {
    stubBoardLoad();
    CustomerIssueDefectSummaryBoardService service = newService();

    StatisticBoardResponse response = service.loadBoard(Map.of());

    StatisticRowData total = row(response, "__total__");
    assertThat(cell(total, "module_total").numericValue()).isEqualTo(2);
  }

  @Test
  void test_summary_export_writes_p2_and_p3_close_rates() throws Exception {
    stubBoardLoad();
    CustomerIssueDefectSummaryBoardService service = newService();

    StatisticBoardResponse response = service.loadBoard(Map.of());
    byte[] content = service.exportBoardWorkbook(Map.of());

    StatisticRowData total = row(response, "__total__");
    assertThat(cell(total, "p2_close_rate").displayValue()).isEqualTo("100.00%");
    assertThat(cell(total, "p3_close_rate").displayValue()).isEqualTo("0.00%");
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheet("客户问题缺陷汇总");
      int p2Column = findHeaderColumn(sheet, "P2缺陷关闭率(%)");
      int p3Column = findHeaderColumn(sheet, "P3缺陷关闭率(%)");
      Row totalRow = findDataRow(sheet, "总计");
      assertThat(p2Column).isNotNegative();
      assertThat(p3Column).isNotNegative();
      assertThat(totalRow.getCell(p2Column).getStringCellValue()).isEqualTo("100.00%");
      assertThat(totalRow.getCell(p3Column).getStringCellValue()).isEqualTo("0.00%");
    }
  }

  @Test
  void test_issue_export_uses_stable_empty_phase_and_affected_function_values() throws Exception {
    CustomerIssueDefectSummaryBoardService service = newService();

    byte[] content = service.exportIssueRecordsWorkbook(Map.of());

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheet("议题数据");
      List<String> headers = values(sheet.getRow(0));
      List<String> knownAffectedRow = valuesForIssue(sheet, headers, "#1001");
      List<String> newlyIdentifiedAffectedRow = valuesForIssue(sheet, headers, "#1002");
      assertThat(headers).hasSize(31);
      assertThat(knownAffectedRow.get(headers.indexOf("测试阶段")))
          .isEqualTo("未设定测试阶段");
      assertThat(knownAffectedRow.get(headers.indexOf("已知的受影响功能"))).isEqualTo("是");
      assertThat(knownAffectedRow.get(headers.indexOf("新识别的受影响功能"))).isEqualTo("否");
      assertThat(newlyIdentifiedAffectedRow.get(headers.indexOf("已知的受影响功能")))
          .isEqualTo("否");
      assertThat(newlyIdentifiedAffectedRow.get(headers.indexOf("新识别的受影响功能")))
          .isEqualTo("是");
    }
  }

  private CustomerIssueDefectSummaryBoardService newService() {
    return new CustomerIssueDefectSummaryBoardService(
        new JsonUtils(new ObjectMapper()),
        customerIssueScopeProfile,
        runtimeSupport,
        issueFactQueryService,
        issueLinkSupport,
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory,
        issueFactRecordRepository);
  }

  @SuppressWarnings("unchecked")
  private void stubBoardLoad() throws Exception {
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-defect-summary", "test", "test", "test", Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(invocation -> ((Supplier<StatisticBoardResponse>) invocation.getArgument(1)).get())
        .when(snapshotService)
        .readOrRefresh(any(), any());
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(2);
              return List.of(mapper.mapRow(moduleCatalogRow(), 0));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyList(), any(RowMapper.class));
  }

  private ResultSet issue(
      int issueIid, String priority, boolean closed, String affectedFunction) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn((long) issueIid);
    when(rs.getInt("iid")).thenReturn(issueIid);
    when(rs.getString("source_instance")).thenReturn("default");
    when(rs.getString("title")).thenReturn("客户问题");
    when(rs.getLong("project_id")).thenReturn(325L);
    when(rs.getString("project_name")).thenReturn("CC_Product");
    when(rs.getString("milestone_title")).thenReturn("CC2026 R3");
    when(rs.getString("author_name")).thenReturn("author");
    when(rs.getTimestamp("created_at"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 6, 1, 9, 0)));
    when(rs.getTimestamp("updated_at"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 6, 2, 9, 0)));
    when(rs.getTimestamp("closed_at"))
        .thenReturn(closed ? Timestamp.valueOf(LocalDateTime.of(2026, 6, 3, 9, 0)) : null);
    when(rs.getString("issue_state")).thenReturn(closed ? "closed" : "opened");
    when(rs.getString("testing_phase")).thenReturn("");
    when(rs.getString("system_test_label")).thenReturn("");
    when(rs.getString("severity_level")).thenReturn("二级缺陷");
    when(rs.getString("priority_level")).thenReturn(priority);
    when(rs.getString("bug_status")).thenReturn("处理中");
    when(rs.getString("category")).thenReturn("缺陷");
    when(rs.getString("reason_category"))
        .thenReturn(
            "已解决 具体原因, 请描述：原因"
                + "4、修改方案：方案"
                + "5、是否由修改其他缺陷引起*否"
                + "6、修改该缺陷可能影响的功能：*"
                + affectedFunction
                + "："
                + "7、是否对可能影响的功能进行了测试*是"
                + "8、有无遗留问题或潜在的影响？*无"
                + "9、是否更新了关联关系表*是");
    when(rs.getString("delay_cause")).thenReturn("");
    when(rs.getString("assignee_name")).thenReturn("assignee");
    when(rs.getString("fix_user")).thenReturn("");
    when(rs.getString("function_name")).thenReturn("装配");
    when(rs.getString("module_names")).thenReturn("装配");
    when(rs.getString("label_names")).thenReturn("");
    return rs;
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

  private Row findDataRow(Sheet sheet, String rowLabel) {
    return IntStream.rangeClosed(0, sheet.getLastRowNum())
        .mapToObj(sheet::getRow)
        .filter(row -> row != null && row.getCell(0) != null)
        .filter(row -> rowLabel.equals(row.getCell(0).getStringCellValue()))
        .findFirst()
        .orElseThrow();
  }

  private List<String> valuesForIssue(
      Sheet sheet, List<String> headers, String issueReference) {
    int issueReferenceIndex = headers.indexOf("议题编号");
    return IntStream.rangeClosed(1, sheet.getLastRowNum())
        .mapToObj(rowIndex -> values(sheet.getRow(rowIndex)))
        .filter(row -> issueReference.equals(row.get(issueReferenceIndex)))
        .findFirst()
        .orElseThrow();
  }

  private List<String> values(Row row) {
    return IntStream.range(0, row.getLastCellNum())
        .mapToObj(index -> row.getCell(index).getStringCellValue())
        .toList();
  }

  private ResultSet moduleCatalogRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("module_names")).thenReturn("装配");
    return rs;
  }

  private StatisticRowData row(StatisticBoardResponse response, String rowKey) {
    return response.rows().stream()
        .filter(row -> rowKey.equals(row.rowKey()))
        .findFirst()
        .orElseThrow();
  }

  private StatisticCellData cell(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow();
  }
}
