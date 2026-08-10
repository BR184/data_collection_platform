package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CustomerIssueSummaryWorkbookExportSupportTest {
  @Test
  void writesAllLegacyCustomerSummaryIssueColumnsInContractOrder() throws Exception {
    CustomerIssueSummaryWorkbookRow row =
        new CustomerIssueSummaryWorkbookRow(
            "2026-07-20", "2026-07-19", "草图", "#101", "标题", "提交人", "处理人", "OPEN",
            "已修复/完成", "系统测试", "一级缺陷", "缺陷", "CC2026R3", "处理人", "P1", "技术卡点",
            "修复人", "约束", "已解决", "需求阶段", "需求理解有误", "具体原因", "修改方案", "否",
            "约束功能", "是", "否", "是", "无", "是", "2026-07-20");

    byte[] content = CustomerIssueSummaryWorkbookExportSupport.export(List.of(row));

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      assertThat(workbook.sheetIterator())
          .toIterable()
          .extracting(sheet -> sheet.getSheetName())
          .containsExactly("议题数据");
      assertThat(values(workbook.getSheet("议题数据").getRow(0)))
          .containsExactlyElementsOf(CustomerIssueSummaryWorkbookRow.HEADERS);
      assertThat(values(workbook.getSheet("议题数据").getRow(1)))
          .containsExactlyElementsOf(row.values());
      assertThat(CustomerIssueSummaryWorkbookRow.HEADERS)
          .hasSize(31)
          .containsSequence(
              "修改该缺陷可能影响的功能",
              "已知的受影响功能",
              "新识别的受影响功能",
              "是否对可能影响的功能进行了测试");
    }
  }

  private List<String> values(Row row) {
    return IntStream.range(0, row.getLastCellNum())
        .mapToObj(index -> row.getCell(index).getStringCellValue())
        .toList();
  }
}
