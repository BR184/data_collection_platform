package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 统计看板金标测试基类：固定夹具与筛选矩阵下锁定 board 响应 JSON 与导出内容指纹。
 *
 * <p>快照缺失时自动生成放行；已存在则逐字节比对。子类只需提供 mock 装配、
 * {@link #goldenDir()}、{@link #filterMatrix()} 与被测服务实例。
 * 易变字段（generatedAt/queryDurationMs）在序列化时屏蔽；工作簿使用单元格内容
 * 语义指纹而非字节哈希（XLSX 内嵌时间戳不可复现）。重构安全网，见
 * docs/plans/statistics-board-framework-refactor.md。
 */
public abstract class AbstractStatisticBoardGoldenMasterTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** 金标快照目录，如 src/test/resources/golden/customer-issue-defect-summary。 */
  protected abstract Path goldenDir();

  /** 筛选矩阵：caseId → filterGroup JSON。键即快照文件名。 */
  protected abstract Map<String, String> filterMatrix();

  /** 被测服务：对每个矩阵用例执行 loadBoard(Map.of("filterGroup", json))。 */
  protected abstract AbstractStatisticBoardService service();

  protected ObjectMapper objectMapper() {
    return objectMapper;
  }

  /** 可选补充校验：默认空实现；子类可追加工作簿指纹等额外快照。 */
  protected List<String> extraGoldenChecks() throws Exception {
    return List.of();
  }

  public final void runGoldenMaster() throws Exception {
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> entry : filterMatrix().entrySet()) {
      StatisticBoardResponse response =
          service().loadBoard(Map.of("filterGroup", entry.getValue()));
      failures.addAll(compareOrCreate(entry.getKey() + ".json", canonicalJson(response)));
    }
    failures.addAll(extraGoldenChecks());
    if (!failures.isEmpty()) {
      throw new AssertionError("金标比对失败：\n" + String.join("\n", failures));
    }
  }

  /** 序列化看板响应并屏蔽易变字段，保证金标只锁定稳定行为。 */
  private String canonicalJson(StatisticBoardResponse response) throws Exception {
    if (!(objectMapper.valueToTree(response) instanceof ObjectNode tree)) {
      return String.valueOf(response);
    }
    if (tree.has("meta") && tree.get("meta") instanceof ObjectNode meta) {
      meta.put("generatedAt", "<masked>");
      meta.put("queryDurationMs", 0);
    }
    return objectMapper.writeValueAsString(tree);
  }

  protected final List<String> compareOrCreate(String fileName, String actual) throws Exception {
    Files.createDirectories(goldenDir());
    Path golden = goldenDir().resolve(fileName);
    if (!Files.exists(golden)) {
      Files.writeString(golden, actual + System.lineSeparator(), StandardCharsets.UTF_8);
      return List.of();
    }
    String expected = Files.readString(golden, StandardCharsets.UTF_8).strip();
    String stripped = actual.strip();
    if (expected.equals(stripped)) {
      return List.of();
    }
    int at = 0;
    while (at < expected.length() && at < stripped.length() && expected.charAt(at) == stripped.charAt(at)) {
      at++;
    }
    return List.of(
        fileName + " 与金标不一致 @" + at
            + "\n  预期片段: " + expected.substring(Math.max(0, at - 60), Math.min(expected.length(), at + 120))
            + "\n  实际片段: " + stripped.substring(Math.max(0, at - 60), Math.min(stripped.length(), at + 120)));
  }

  /** 工作簿语义指纹：按 sheet/行/列顺序拼接单元格文本后取 SHA-256。 */
  protected final String workbookFingerprint(byte[] content) throws Exception {
    StringBuilder canonical = new StringBuilder();
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        canonical.append('[').append(sheet.getSheetName()).append(']');
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (row == null) {
            continue;
          }
          for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            Cell cell = row.getCell(cellIndex);
            if (cell == null) {
              continue;
            }
            String value = switch (cell.getCellType()) {
              case STRING -> cell.getStringCellValue();
              case NUMERIC -> java.math.BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
              case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
              case FORMULA -> cell.getCellFormula();
              default -> "";
            };
            canonical.append('(').append(cellIndex).append(')').append(value);
          }
          canonical.append('|');
        }
      }
    }
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  protected final String sha256(byte[] content) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
