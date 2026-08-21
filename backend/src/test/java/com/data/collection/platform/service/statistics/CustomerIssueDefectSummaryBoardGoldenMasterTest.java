package com.data.collection.platform.service.statistics;

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
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/**
 * 客户问题缺陷汇总看板金标测试：在固定夹具与筛选矩阵下锁定 board 响应 JSON 与导出字节。
 *
 * <p>快照文件缺失时自动生成并放行（首次落盘）；已存在则逐字节比对，任何行为漂移直接失败。
 * 这是看板框架重构（docs/plans/statistics-board-framework-refactor.md）的安全网。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectSummaryBoardGoldenMasterTest {

  private static final Path GOLDEN_DIR =
      Path.of("src", "test", "resources", "golden", "customer-issue-defect-summary");

  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @BeforeEach
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

  @Test
  void test_golden_master_locks_board_response_across_filter_matrix() throws Exception {
    CustomerIssueDefectSummaryBoardService service = newService();
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> entry : filterMatrix().entrySet()) {
      String caseId = entry.getKey();
      StatisticBoardResponse response =
          service.loadBoard(Map.of("filterGroup", entry.getValue()));
      String actual = canonicalJson(response);
      failures.addAll(compareOrCreate(caseId + ".json", actual));
    }
    byte[] workbook = service.exportIssueRecordsWorkbook(Map.of());
    failures.addAll(compareOrCreate("issue-records-workbook.fingerprint", workbookFingerprint(workbook)));
    byte[] boardWorkbook = service.exportBoardWorkbook(Map.of());
    failures.addAll(compareOrCreate("board-workbook.fingerprint", workbookFingerprint(boardWorkbook)));
    if (!failures.isEmpty()) {
      throw new AssertionError("金标比对失败：\n" + String.join("\n", failures));
    }
  }

  /** 覆盖全部操作符族的代表性筛选矩阵；键即金标快照 caseId。 */
  private Map<String, String> filterMatrix() {
    Map<String, String> matrix = new LinkedHashMap<>();
    matrix.put("empty-group", "{}");
    matrix.put("project-name-eq-hit",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"CC_Product\"}]}");
    matrix.put("project-name-eq-miss",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"Other\"}]}");
    matrix.put("project-name-ne",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"ne\",\"value\":\"CC_Product\"}]}");
    matrix.put("title-contains",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"title\",\"operator\":\"contains\",\"value\":\"客户\"}]}");
    matrix.put("issue-state-eq-closed",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"issueState\",\"operator\":\"eq\",\"value\":\"closed\"}]}");
    matrix.put("category-eq-hit",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"category\",\"operator\":\"eq\",\"value\":\"缺陷\"}]}");
    matrix.put("category-ne-miss",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"category\",\"operator\":\"ne\",\"value\":\"缺陷\"}]}");
    matrix.put("module-intersects",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"组\",\"operator\":\"intersects\",\"values\":[\"装配\"]}]}");
    matrix.put("module-not-intersects",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"组\",\"operator\":\"notIntersects\",\"values\":[\"喷涂\"]}]}");
    matrix.put("module-partial-contains-any",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"组\",\"operator\":\"partialContainsAny\",\"values\":[\"配\"]}]}");
    matrix.put("bug-status-label-group",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":2,\"labelGroupName\":\"状态组\",\"operator\":\"intersects\",\"values\":[\"处理中\"]}]}");
    matrix.put("bug-status-plain-ne",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"operator\":\"ne\",\"value\":\"处理中\"}]}");
    matrix.put("milestone-label-group",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"milestoneTitle\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":3,\"labelGroupName\":\"里程碑\",\"operator\":\"intersects\",\"values\":[\"CC2026R3\"]}]}");
    matrix.put("or-combo",
        "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"Other\"},{\"fieldKey\":\"title\",\"operator\":\"contains\",\"value\":\"客户\"}]}");
    return matrix;
  }

  /** 序列化看板响应并屏蔽易变字段（如生成时间戳），保证金标只锁定稳定行为。 */
  private String canonicalJson(StatisticBoardResponse response) throws Exception {
    ObjectNode tree = (ObjectNode) objectMapper.valueToTree(response);
    if (tree.has("meta") && tree.get("meta") instanceof ObjectNode meta) {
      meta.put("generatedAt", "<masked>");
      meta.put("queryDurationMs", 0);
    }
    return objectMapper.writeValueAsString(tree);
  }

  private List<String> compareOrCreate(String fileName, String actual) throws Exception {
    Files.createDirectories(GOLDEN_DIR);
    Path golden = GOLDEN_DIR.resolve(fileName);
    if (!Files.exists(golden)) {
      Files.writeString(golden, actual + System.lineSeparator(), StandardCharsets.UTF_8);
      return List.of();
    }
    String expected = Files.readString(golden, StandardCharsets.UTF_8).strip();
    if (expected.equals(actual.strip())) {
      return List.of();
    }
    String snippetExpected = expected.substring(0, Math.min(400, expected.length()));
    String snippetActual = actual.strip().substring(0, Math.min(400, actual.strip().length()));
    int at = 0;
    while (at < expected.length() && at < actual.strip().length()
        && expected.charAt(at) == actual.strip().charAt(at)) {
      at++;
    }
    return List.of(
        fileName + " 与金标不一致 @" + at
            + "\n  预期片段: " + expected.substring(Math.max(0, at - 60), Math.min(expected.length(), at + 120))
            + "\n  实际片段: " + actual.strip().substring(Math.max(0, at - 60), Math.min(actual.strip().length(), at + 120))
            + "\n  开头预期: " + snippetExpected
            + "\n  开头实际: " + snippetActual);

  }

  /**
   * 工作簿语义指纹：按 sheet/行/列顺序拼接全部单元格文本后取 SHA-256。
   * 不比对字节，因为 XLSX 内嵌创建时间戳等元数据天然不可复现。
   */
  private String workbookFingerprint(byte[] content) throws Exception {
    StringBuilder canonical = new StringBuilder();
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(sheetIndex);
        canonical.append('[').append(sheet.getSheetName()).append(']');
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
          Row row = sheet.getRow(rowIndex);
          if (row == null) {
            continue;
          }
          for (int cellIndex = 0; cellIndex < row.getLastCellNum(); cellIndex++) {
            org.apache.poi.ss.usermodel.Cell cell = row.getCell(cellIndex);
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

  private String sha256(byte[] content) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
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

  private ResultSet moduleCatalogRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("module_names")).thenReturn("装配");
    return rs;
  }
}
