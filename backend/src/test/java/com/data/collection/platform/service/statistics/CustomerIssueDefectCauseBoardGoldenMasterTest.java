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
import com.data.collection.platform.service.RealtimeIncrementalRefreshService;
import com.data.collection.platform.service.RealtimeWorkspaceService;
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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/**
 * 客户问题缺陷原因看板金标测试：固定夹具与筛选矩阵下锁定 board 响应与导出内容。
 * 快照缺失时自动生成放行；已存在则逐字节比对。重构安全网，见
 * docs/plans/statistics-board-framework-refactor.md。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectCauseBoardGoldenMasterTest {

  private static final Path GOLDEN_DIR =
      Path.of("src", "test", "resources", "golden", "customer-issue-defect-cause");

  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @BeforeEach
  void setUp() throws Exception {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026R3"));
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    when(milestoneCatalogService.matches("CC2026R3", "CC2026 R3")).thenReturn(true);
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-defect-cause", "test", "test", "test", Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(invocation -> ((Supplier<StatisticBoardResponse>) invocation.getArgument(1)).get())
        .when(snapshotService)
        .readOrRefresh(any(), any());
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(4);
              return List.of(
                  mapper.mapRow(row(2077, "工程图, 平台", "前置数据异常（如缺少模板文件、前置输入文件本身错误等）"), 0),
                  mapper.mapRow(row(2114, "工程图, 草图", "新增需求"), 1),
                  mapper.mapRow(row(2277, "", "新增理解偏差"), 2),
                  mapper.mapRow(row(3001, "草图", "提示信息不合理"), 3),
                  mapper.mapRow(row(3002, "平台", "调用接口错误"), 4),
                  mapper.mapRow(row(3003, "平台", "前置数据异常"), 5));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
  }

  @Test
  void test_golden_master_locks_board_response_across_filter_matrix() throws Exception {
    CustomerIssueDefectCauseBoardService service = newService();
    List<String> failures = new ArrayList<>();
    for (Map.Entry<String, String> entry : filterMatrix().entrySet()) {
      StatisticBoardResponse response =
          service.loadBoard(Map.of("filterGroup", entry.getValue()));
      failures.addAll(compareOrCreate(entry.getKey() + ".json", canonicalJson(response)));
    }
    byte[] boardWorkbook = service.exportBoardWorkbook(Map.of());
    failures.addAll(compareOrCreate("board-workbook.fingerprint", workbookFingerprint(boardWorkbook)));
    if (!failures.isEmpty()) {
      throw new AssertionError("金标比对失败：\n" + String.join("\n", failures));
    }
  }

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
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"title\",\"operator\":\"contains\",\"value\":\"2077\"}]}");
    matrix.put("module-contains",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"工程图\"}]}");
    matrix.put("category-eq",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"category\",\"operator\":\"eq\",\"value\":\"缺陷\"}]}");
    matrix.put("bug-status-eq",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"operator\":\"eq\",\"value\":\"已解决\"}]}");
    matrix.put("bug-status-ne",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"operator\":\"ne\",\"value\":\"已解决\"}]}");
    matrix.put("milestone-label-group",
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"milestoneTitle\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":3,\"labelGroupName\":\"里程碑\",\"operator\":\"intersects\",\"values\":[\"CC2026R3\"]}]}");
    matrix.put("or-combo",
        "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"草图\"},{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"平台\"}]}");
    return matrix;
  }

  /** 序列化看板响应并屏蔽易变字段，保证金标只锁定稳定行为。 */
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
    int at = 0;
    String stripped = actual.strip();
    while (at < expected.length() && at < stripped.length() && expected.charAt(at) == stripped.charAt(at)) {
      at++;
    }
    return List.of(
        fileName + " 与金标不一致 @" + at
            + "\n  预期片段: " + expected.substring(Math.max(0, at - 60), Math.min(expected.length(), at + 120))
            + "\n  实际片段: " + stripped.substring(Math.max(0, at - 60), Math.min(stripped.length(), at + 120)));
  }

  /** 工作簿语义指纹：按 sheet/行/列顺序拼接单元格文本后取 SHA-256（XLSX 元数据不可复现）。 */
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

  private CustomerIssueDefectCauseBoardService newService() {
    return new CustomerIssueDefectCauseBoardService(
        new JsonUtils(new ObjectMapper()),
        realtimeWorkspaceService,
        realtimeIncrementalRefreshService,
        issueFactQueryService,
        new CustomerIssueScopeProfile(),
        issueLinkSupport,
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory);
  }

  private ResultSet row(int iid, String modules, String reason) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn((long) iid);
    when(rs.getInt("iid")).thenReturn(iid);
    when(rs.getString("source_instance")).thenReturn("default");
    when(rs.getString("title")).thenReturn("issue " + iid);
    when(rs.getLong("project_id")).thenReturn(325L);
    when(rs.getString("project_name")).thenReturn("CC_Product");
    when(rs.getString("milestone_title")).thenReturn("CC2026 R3");
    when(rs.getString("author_name")).thenReturn("author");
    when(rs.getString("assignee_name")).thenReturn("assignee");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 6, 1, 9, 0)));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 7, 6, 9, 0)));
    when(rs.getTimestamp("closed_at")).thenReturn(null);
    when(rs.getString("issue_state")).thenReturn("opened");
    when(rs.getString("bug_status")).thenReturn("已解决");
    when(rs.getString("severity_level")).thenReturn("LEVEL2");
    when(rs.getString("category")).thenReturn("缺陷");
    when(rs.getString("testing_phase")).thenReturn("");
    when(rs.getString("system_test_label")).thenReturn("");
    when(rs.getString("reason_category")).thenReturn(reason);
    when(rs.getString("module_names")).thenReturn(modules);
    when(rs.getString("label_names")).thenReturn("");
    when(rs.getBoolean("is_excluded")).thenReturn(false);
    return rs;
  }
}
