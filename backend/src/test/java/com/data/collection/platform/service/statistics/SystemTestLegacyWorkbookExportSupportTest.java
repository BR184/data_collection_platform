package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SystemTestLegacyWorkbookExportSupportTest {
  @Test
  void test_defect_summary_export_writes_p2_and_p3_close_rate_columns() throws Exception {
    StatisticRowData row =
        new StatisticRowData(
            "草图",
            "草图",
            List.of(
                cell("p2_close_rate", "50.00"),
                cell("p3_close_rate", "25.00")));
    StatisticBoardResponse response =
        new StatisticBoardResponse(null, Map.of(), null, List.of(row), null);

    byte[] content = SystemTestLegacyWorkbookExportSupport.exportDefectSummary(response);

    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheet("系统测试缺陷汇总统计");
      int p2Column = findHeaderColumn(sheet, "P2缺陷关闭率(%)");
      int p3Column = findHeaderColumn(sheet, "P3缺陷关闭率(%)");
      assertThat(p2Column).isNotNegative();
      assertThat(p3Column).isEqualTo(p2Column + 3);
      assertThat(sheet.getRow(3).getCell(p2Column).getStringCellValue()).isEqualTo("50.00");
      assertThat(sheet.getRow(3).getCell(p3Column).getStringCellValue()).isEqualTo("25.00");
    }
  }

  private StatisticCellData cell(String key, String value) {
    return new StatisticCellData(key, 0L, value, false, null, Map.of());
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
}
