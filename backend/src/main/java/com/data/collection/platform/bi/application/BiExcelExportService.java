package com.data.collection.platform.bi.application;

import com.data.collection.platform.service.ExcelExportStyles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 把前端按图表语义提取的表格数据序列化为标准 OOXML（.xlsx）工作簿。
 *
 * <p>职责边界：前端拥有"导出哪些单元格"（各图表类的 {@code excelTable}），本服务只拥有
 * "如何把表头与数据行写成真正的 Excel 文件"。因此本服务不理解任何图表业务语义，也不重算数据，
 * 只按传入的标题、产品版本名、表头与数据行生成带标题行、元信息行、冻结表头和统一样式的工作簿。</p>
 *
 * <p>无状态、无启动副作用，由 {@code BiDashboardRuntimeFactory} 显式装配为普通 Java 对象。
 * 表头/数据样式复用平台成熟基础设施 {@link ExcelExportStyles}，与全平台其它 Excel 导出视觉一致。</p>
 */
public final class BiExcelExportService {
  private static final String FONT_NAME = "微软雅黑";
  private static final String DEFAULT_SHEET_NAME = "BI图表";
  private static final String DEFAULT_FILE_BASE = "BI图表";
  private static final DateTimeFormatter META_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  // Excel 工作表名上限 31 字符，且不得包含 : \ / ? * [ ]。
  private static final int SHEET_NAME_MAX_LENGTH = 31;
  private static final int MIN_COLUMN_CHARS = 10;
  private static final int MAX_COLUMN_CHARS = 60;
  private static final String EXPLANATION_PREFIX = "口径说明：";
  private static final int TITLE_ROW_INDEX = 0;
  private static final int META_ROW_INDEX = 1;

  private final Clock clock;

  public BiExcelExportService() {
    this(Clock.systemDefaultZone());
  }

  BiExcelExportService(Clock clock) {
    this.clock = clock;
  }

  /** 导出产物：工作簿字节与建议下载文件名（时间戳与元信息行取自同一时刻）。 */
  public record Export(byte[] content, String filename) {}

  /**
   * 生成标准 .xlsx 工作簿。
   *
   * @param title 图表标题，同时用于工作表名（消毒后）与文件名主干；不可为空
   * @param productVersionName 产品版本可读名，写入元信息行；为空时以占位符呈现
   * @param explanation 该图表的业务口径与达标标准说明，取自看板问号词条；空白时不占用行
   * @param headers 表头文本，决定列数与列宽；不可为空
   * @param rows 数据行，单元格为 {@link Number}（写数值）或其它（写文本，{@code null} 视为空串）
   * @return 工作簿字节与建议文件名
   */
  public Export export(
      String title,
      String productVersionName,
      String explanation,
      List<String> headers,
      List<List<Object>> rows) {
    LocalDateTime now = LocalDateTime.now(clock);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(sheetName(title));
      CellStyle titleStyle = createTitleStyle(workbook);
      CellStyle metaStyle = createMetaStyle(workbook);
      CellStyle explanationStyle = createExplanationStyle(workbook);
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);

      Row titleRow = sheet.createRow(TITLE_ROW_INDEX);
      titleRow.setHeightInPoints(24F);
      writeText(titleRow, 0, title, titleStyle);

      Row metaRow = sheet.createRow(META_ROW_INDEX);
      metaRow.setHeightInPoints(16F);
      writeText(metaRow, 0, metaText(productVersionName, now), metaStyle);

      // 上方说明区块的行数可变（口径说明可能缺省），因此表头与数据行位置由游标推导，
      // 保证冻结窗格在任何组合下都恰好覆盖到表头为止。
      int nextRow = META_ROW_INDEX + 1;
      if (explanation != null && !explanation.isBlank()) {
        Row explanationRow = sheet.createRow(nextRow);
        explanationRow.setHeightInPoints(16F);
        writeText(explanationRow, 0, EXPLANATION_PREFIX + explanation.trim(), explanationStyle);
        nextRow++;
      }
      // 空行分隔上方的说明区块与表头。
      sheet.createRow(nextRow);
      int headerRowIndex = nextRow + 1;
      int firstDataRowIndex = headerRowIndex + 1;

      Row headerRow = sheet.createRow(headerRowIndex);
      headerRow.setHeightInPoints(22F);
      for (int column = 0; column < headers.size(); column++) {
        writeText(headerRow, column, headers.get(column), headerStyle);
      }

      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Row row = sheet.createRow(firstDataRowIndex + rowIndex);
        List<Object> cells = rows.get(rowIndex);
        for (int column = 0; column < cells.size(); column++) {
          writeValue(row, column, cells.get(column), bodyStyle);
        }
      }

      // 冻结标题、元信息、（可选）口径说明、空行与表头，滚动数据时表头常驻。
      sheet.createFreezePane(0, firstDataRowIndex);
      // 列宽只依据表头与数据计算，避免标题/元信息长文本撑爆首列；不使用依赖 AWT 字体度量的自动列宽。
      ExcelExportStyles.setReadableColumnWidths(sheet, columnWidths(headers, rows));

      workbook.write(output);
      return new Export(output.toByteArray(), filename(title, now));
    } catch (IOException error) {
      throw new IllegalStateException("生成 BI 图表 Excel 失败", error);
    }
  }

  private String metaText(String productVersionName, LocalDateTime now) {
    String version =
        productVersionName == null || productVersionName.isBlank() ? "--" : productVersionName;
    return "产品版本：" + version + "  |  导出时间：" + now.format(META_TIME);
  }

  private String sheetName(String title) {
    String cleaned = sanitize(title, "[:\\\\/?*\\[\\]]", " ").trim();
    if (cleaned.isEmpty()) {
      return DEFAULT_SHEET_NAME;
    }
    return cleaned.length() > SHEET_NAME_MAX_LENGTH
        ? cleaned.substring(0, SHEET_NAME_MAX_LENGTH)
        : cleaned;
  }

  private String filename(String title, LocalDateTime now) {
    String base = sanitize(title, "[\\\\/:*?\"<>|]", "-").trim();
    if (base.isEmpty()) {
      base = DEFAULT_FILE_BASE;
    }
    return base + "_" + now.format(FILE_TIME) + ".xlsx";
  }

  private String sanitize(String value, String regex, String replacement) {
    return value == null ? "" : value.replaceAll(regex, replacement);
  }

  private int[] columnWidths(List<String> headers, List<List<Object>> rows) {
    int count = headers.size();
    int[] widths = new int[count];
    for (int column = 0; column < count; column++) {
      widths[column] = displayWidth(headers.get(column));
    }
    for (List<Object> row : rows) {
      for (int column = 0; column < row.size() && column < count; column++) {
        Object cell = row.get(column);
        widths[column] = Math.max(widths[column], displayWidth(cell == null ? "" : cell.toString()));
      }
    }
    for (int column = 0; column < count; column++) {
      widths[column] = Math.min(Math.max(widths[column] + 2, MIN_COLUMN_CHARS), MAX_COLUMN_CHARS);
    }
    return widths;
  }

  /** 估算显示宽度：CJK 及全角字符按两列计，其余按一列计。 */
  private int displayWidth(String value) {
    int width = 0;
    for (int index = 0; index < value.length(); index++) {
      width += value.charAt(index) > 0x2E80 ? 2 : 1;
    }
    return width;
  }

  private CellStyle createTitleStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setFontName(FONT_NAME);
    font.setFontHeightInPoints((short) 14);
    font.setBold(true);
    style.setFont(font);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    return style;
  }

  private CellStyle createMetaStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setFontName(FONT_NAME);
    font.setFontHeightInPoints((short) 9);
    font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
    style.setFont(font);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    return style;
  }

  private CellStyle createExplanationStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setFontName(FONT_NAME);
    font.setFontHeightInPoints((short) 10);
    style.setFont(font);
    style.setVerticalAlignment(VerticalAlignment.CENTER);
    return style;
  }

  private void writeText(Row row, int column, String value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellStyle(style);
    cell.setCellValue(value == null ? "" : value);
  }

  private void writeValue(Row row, int column, Object value, CellStyle style) {
    Cell cell = row.createCell(column);
    cell.setCellStyle(style);
    if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
      return;
    }
    cell.setCellValue(value == null ? "" : value.toString());
  }
}
