package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SystemTestIssueRecordWorkbookExportSupportTest {
  @Test
  void test_defect_summary_issue_export_adds_affected_function_columns_only_to_target_layout()
      throws Exception {
    SystemTestIssueSearchRowResponse source = sourceRow();

    byte[] summaryContent =
        SystemTestIssueRecordWorkbookExportSupport.exportIssueDataRecords(List.of(source));
    byte[] searchContent =
        SystemTestIssueRecordWorkbookExportSupport.exportRecords(List.of(source));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(summaryContent))) {
      List<String> headers = values(workbook.getSheet("议题数据").getRow(0));
      List<String> row = values(workbook.getSheet("议题数据").getRow(1));
      int knownIndex = headers.indexOf("已知的受影响功能");
      int newlyIdentifiedIndex = headers.indexOf("新识别的受影响功能");

      assertThat(headers).hasSize(31);
      assertThat(knownIndex).isGreaterThan(headers.indexOf("修改该缺陷可能影响的功能"));
      assertThat(newlyIdentifiedIndex).isEqualTo(knownIndex + 1);
      assertThat(row.get(knownIndex)).isEqualTo("是");
      assertThat(row.get(newlyIdentifiedIndex)).isEqualTo("否");
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(searchContent))) {
      assertThat(values(workbook.getSheet("多元查询结果").getRow(0)))
          .hasSize(29)
          .doesNotContain("已知的受影响功能", "新识别的受影响功能");
    }
  }

  private SystemTestIssueSearchRowResponse sourceRow() {
    return new SystemTestIssueSearchRowResponse(
        1L,
        101,
        "",
        "default",
        9L,
        "CrownCAD",
        "议题标题",
        "closed",
        "CC2026R4第一轮系统测试",
        "LEVEL2",
        "P2",
        "已修复/完成",
        "缺陷",
        "CC2026R4",
        "",
        "提交人",
        "处理人",
        "草图",
        "约束",
        "修复人",
        "已解决",
        "设计问题",
        "设计方案不合理",
        "具体原因",
        "修改方案",
        "否",
        "已知的受影响功能：",
        "是",
        "无",
        "是",
        LocalDateTime.of(2026, 7, 1, 9, 0),
        LocalDateTime.of(2026, 7, 2, 9, 0),
        LocalDateTime.of(2026, 7, 3, 9, 0),
        List.of());
  }

  private List<String> values(Row row) {
    return IntStream.range(0, row.getLastCellNum())
        .mapToObj(index -> row.getCell(index).getStringCellValue())
        .toList();
  }
}
