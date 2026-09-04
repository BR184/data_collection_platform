package com.data.collection.platform.golden;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.core.ConfigurationWhen.paths;
import static net.javacrumbs.jsonunit.core.ConfigurationWhen.then;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.javacrumbs.jsonunit.assertj.JsonAssert.ConfigurableJsonAssert;
import net.javacrumbs.jsonunit.core.Option;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 黄金基线快照引擎：JSON/导出内容的规范化、严格比对与显式更新。
 *
 * <p>与既有统计金标（缺失自动生成放行）不同，快照严格模式：缺失即失败，
 * 重新生成必须显式 {@code -Dgolden.update=true}，生成后经 git diff 人工审阅。
 * 快照文件位于 {@code src/test/resources/golden-baseline/snapshots/}。
 * 导出内容（Excel/CSV）规范化为"sheet → 行 → 单元格"JSON，diff 可读；
 * Excel 数值/公式单元格语义与统计金标指纹保持一致。</p>
 */
public final class GoldenSnapshotSupport {

  /** 更新模式系统属性：{@code -Dgolden.update=true}。 */
  public static final String UPDATE_PROPERTY = "golden.update";

  private static final Path SNAPSHOT_ROOT =
      Path.of("src", "test", "resources", "golden-baseline", "snapshots");

  private static final ObjectMapper PRETTY_JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private GoldenSnapshotSupport() {
  }

  /**
   * 是否处于显式更新模式（{@code -Dgolden.update=true}）。
   *
   * @return 更新模式为真时快照写入而非比对
   */
  public static boolean updateMode() {
    return Boolean.getBoolean(UPDATE_PROPERTY);
  }

  /**
   * 由端点条目与用例推导快照相对路径。
   *
   * @param entry 端点条目
   * @param caseId 用例 id
   * @return 形如 {@code statistic-boards/get__{boardKey}__system-test-defect-summary.json} 的路径
   */
  public static String snapshotName(GoldenEndpointCatalog.EndpointEntry entry, String caseId) {
    String path = entry.path();
    String stripped = path.startsWith("/api/") ? path.substring("/api/".length()) : path.substring(1);
    String controller = stripped.split("/", 2)[0];
    String rest = stripped.substring(controller.length());
    if (controller.isBlank()) {
      // 根路径端点无 controller 段；保留空段会让 resolve 收到前导斜杠路径，把快照写到盘符根目录。
      controller = "home";
    }
    return controller + "/" + entry.method().toLowerCase() + "__"
        + rest.replace('/', '_') + "__" + caseId + ".json";
  }

  /**
   * 断言 JSON 快照：更新模式写入；否则要求快照存在并按 JsonUnit 结构比对
   * （忽略目录声明的易变字段路径与声明路径内的数组顺序）。缺失即失败，杜绝静默遗漏。
   *
   * <p>比对前对实际响应执行与快照写入一致的 Jackson 规范化往返，
   * 消除服务端 BigDecimal 标度（如 {@code 30.30}）与快照标度（{@code 30.3}）的表示差异。</p>
   *
   * @param snapshotName 快照相对路径（见 {@link #snapshotName}）
   * @param actualJson 服务端实际响应 JSON 文本
   * @param ignorePaths JsonUnit 忽略路径（易变字段掩码）
   * @param ignoreArrayOrderPaths 忽略元素顺序的路径（无稳定排序的集合输出）
   * @throws IOException 快照读写失败
   */
  public static void assertJsonSnapshot(
      String snapshotName,
      String actualJson,
      List<String> ignorePaths,
      List<String> ignoreArrayOrderPaths) throws IOException {
    Path target = SNAPSHOT_ROOT.resolve(snapshotName);
    String canonical = prettyOrRaw(actualJson);
    if (updateMode()) {
      writeSnapshot(target, canonical);
      return;
    }
    if (!Files.exists(target)) {
      throw new IllegalStateException(
          "快照缺失: " + snapshotName + "（重新生成必须显式 -D" + UPDATE_PROPERTY + "=true）");
    }
    String expected = Files.readString(target, StandardCharsets.UTF_8);
    try {
      ConfigurableJsonAssert assertion =
          assertThatJson(canonical).as("快照比对失败: " + snapshotName);
      if (!ignoreArrayOrderPaths.isEmpty()) {
        assertion = assertion.when(
            paths(ignoreArrayOrderPaths.toArray(String[]::new)),
            then(Option.IGNORING_ARRAY_ORDER));
      }
      assertion.whenIgnoringPaths(ignorePaths.toArray(String[]::new))
          .isEqualTo(expected);
    } catch (AssertionError e) {
      // .as() 描述不会进入 surefire 消息头；失败信息必须自带快照名，才能定位到端点用例。
      throw new AssertionError("快照比对失败: " + snapshotName + "\n" + e.getMessage(), e);
    }
  }

  /**
   * 断言导出快照（Excel/CSV 等二进制或文本响应体）。
   *
   * <p>Excel 工作簿规范化为"sheet → 行 → 单元格"JSON 后走 JSON 快照路径；
   * 其余内容按 UTF-8 文本原样快照比对（CSV 等）。</p>
   *
   * @param snapshotName 快照相对路径
   * @param content 响应体字节
   * @param ignorePaths 规范化 JSON 的易变字段掩码（仅 Excel 路径生效）
   * @throws IOException 快照读写失败
   */
  public static void assertExportSnapshot(
      String snapshotName, byte[] content, List<String> ignorePaths) throws IOException {
    Path target = SNAPSHOT_ROOT.resolve(snapshotName);
    String excelJson = tryNormalizeExcel(content);
    if (excelJson != null) {
      assertJsonSnapshot(snapshotName, excelJson, ignorePaths, List.of());
      return;
    }
    String text = new String(content, StandardCharsets.UTF_8);
    if (updateMode()) {
      writeSnapshot(target, text);
      return;
    }
    if (!Files.exists(target)) {
      throw new IllegalStateException(
          "快照缺失: " + snapshotName + "（重新生成必须显式 -D" + UPDATE_PROPERTY + "=true）");
    }
    String expected = Files.readString(target, StandardCharsets.UTF_8);
    if (!expected.equals(text)) {
      throw new IllegalStateException("导出快照不一致: " + snapshotName);
    }
  }

  /**
   * 将 Excel 工作簿规范化为"sheet → 行 → 单元格"JSON 文本。
   *
   * <p>行键为 1 起始行号（跳过空行），列键为列字母；字符串原样、数值去尾零、
   * 公式取公式文本，与统计金标指纹的单元格语义一致。</p>
   *
   * @param content 工作簿字节
   * @return 规范化 JSON 文本
   * @throws IOException 解析失败
   */
  public static String normalizeExcel(byte[] content) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();
    ArrayNode sheets = root.putArray("sheets");
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        ObjectNode sheetNode = sheets.addObject();
        sheetNode.put("name", sheet.getSheetName());
        ObjectNode rows = sheetNode.putObject("rows");
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (row == null) {
            continue;
          }
          ObjectNode rowNode = rows.putObject(String.valueOf(rowIndex + 1));
          for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            Cell cell = row.getCell(cellIndex);
            if (cell == null) {
              continue;
            }
            String column = org.apache.poi.ss.util.CellReference.convertNumToColString(cellIndex);
            rowNode.put(column, cellText(cell));
          }
        }
      }
    }
    return PRETTY_JSON.writeValueAsString(root);
  }

  private static String tryNormalizeExcel(byte[] content) {
    try {
      return normalizeExcel(content);
    } catch (Exception e) {
      return null;
    }
  }

  private static String cellText(Cell cell) {
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue())
          .stripTrailingZeros().toPlainString();
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> cell.getCellFormula();
      case BLANK -> "";
      default -> cell.getCellType() == CellType.ERROR
          ? "ERROR_" + cell.getErrorCellValue()
          : "";
    };
  }

  private static String prettyOrRaw(String raw) {
    try {
      JsonNode node = PRETTY_JSON.readTree(raw);
      return PRETTY_JSON.writeValueAsString(node);
    } catch (IOException e) {
      return raw;
    }
  }

  private static void writeSnapshot(Path target, String content) {
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, content + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("写入快照失败: " + target, e);
    }
  }
}
