package com.data.collection.platform.service.statistics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/** 客户问题响应效率看板金标测试：重构安全网（docs/plans/statistics-board-framework-refactor.md）。 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueResponseEfficiencyBoardGoldenMasterTest
    extends AbstractStatisticBoardGoldenMasterTest {

  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    when(milestoneCatalogService.matches("CC2026R3", "CC2026 R3")).thenReturn(true);
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-response-efficiency", "test", "test", "test", Map.of(), null, null);
    lenient()
        .when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    lenient()
        .doAnswer(invocation -> ((Supplier<StatisticBoardResponse>) invocation.getArgument(1)).get())
        .when(snapshotService)
        .readOrRefresh(any(), any());
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(4);
              return List.of(
                  mapper.mapRow(issue(2601, "其他模块 & 二次开发", 1), 0),
                  mapper.mapRow(issue(2602, "二次开发", 8), 1));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
    doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              RowMapper<Object> mapper = invocation.getArgument(2);
              if (sql.contains("author_names")) {
                return List.of(mapper.mapRow(personOptionsRow(), 0));
              }
              return List.of(
                  mapper.mapRow(moduleCatalogRow("其他模块 & 二次开发"), 0),
                  mapper.mapRow(moduleCatalogRow("二次开发"), 1));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyList(), any(RowMapper.class));
  }

  @Test
  void test_golden_master_locks_board_response_across_filter_matrix() throws Exception {
    runGoldenMaster();
  }

  @Override
  protected Path goldenDir() {
    return Path.of("src", "test", "resources", "golden", "customer-issue-response-efficiency");
  }

  @Override
  protected Map<String, String> filterMatrix() {
    return Map.ofEntries(
        Map.entry("empty-group", "{}"),
        Map.entry("project-name-eq-hit",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"CC_Product\"}]}"),
        Map.entry("project-name-eq-miss",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"Other\"}]}"),
        Map.entry("module-contains",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"二次开发\"}]}"),
        Map.entry("severity-eq",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"severityLevel\",\"operator\":\"eq\",\"value\":\"二级缺陷\"}]}"),
        Map.entry("bug-status-label-group",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":2,\"labelGroupName\":\"状态组\",\"operator\":\"intersects\",\"values\":[\"已修复/完成\"]}]}"),
        Map.entry("bug-status-plain-ne",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"operator\":\"ne\",\"value\":\"已修复/完成\"}]}"),
        Map.entry("milestone-label-group",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"milestoneTitle\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":3,\"labelGroupName\":\"里程碑\",\"operator\":\"intersects\",\"values\":[\"CC2026R3\"]}]}"),
        Map.entry("issue-state-eq-closed",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"issueState\",\"operator\":\"eq\",\"value\":\"closed\"}]}"),
        Map.entry("or-combo",
            "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"其他模块\"},{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"平台\"}]}"));
  }

  @Override
  protected AbstractStatisticBoardService service() {
    return new CustomerIssueResponseEfficiencyBoardService(
        new JsonUtils(new ObjectMapper()),
        issueFactQueryService,
        new CustomerIssueScopeProfile(),
        issueLinkSupport,
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory);
  }

  @Override
  protected List<String> extraGoldenChecks() throws Exception {
    byte[] workbook = service().exportBoardWorkbook(Map.of());
    return compareOrCreate("board-workbook.fingerprint", workbookFingerprint(workbook));
  }

  private ResultSet issue(int iid, String moduleNames, int resolutionDays) throws Exception {
    LocalDateTime createdAt = LocalDateTime.of(2026, 4, 1, 9, 0);
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("source_instance")).thenReturn("default");
    when(rs.getLong("project_id")).thenReturn(325L);
    when(rs.getString("project_name")).thenReturn("CC_Product");
    when(rs.getLong("issue_id")).thenReturn((long) iid);
    when(rs.getInt("issue_iid")).thenReturn(iid);
    when(rs.getString("title")).thenReturn("issue " + iid);
    when(rs.getString("issue_state")).thenReturn("opened");
    when(rs.getString("testing_phase")).thenReturn("");
    when(rs.getString("system_test_label")).thenReturn("");
    when(rs.getString("severity_level")).thenReturn("二级缺陷");
    when(rs.getString("priority_level")).thenReturn("P2");
    when(rs.getString("bug_status")).thenReturn("已修复/完成");
    when(rs.getString("category")).thenReturn("功能");
    when(rs.getString("reason_category")).thenReturn("");
    when(rs.getString("milestone_title")).thenReturn("CC2026 R3");
    when(rs.getString("author_name")).thenReturn("author");
    when(rs.getString("assignee_name")).thenReturn("assignee");
    when(rs.getString("module_names")).thenReturn(moduleNames);
    when(rs.getString("label_names")).thenReturn("状态：已修复/完成");
    when(rs.getBoolean("is_excluded")).thenReturn(false);
    when(rs.getTimestamp("research_template_time")).thenReturn(null);
    when(rs.getTimestamp("fixed_label_time"))
        .thenReturn(Timestamp.valueOf(createdAt.plusDays(resolutionDays)));
    when(rs.getTimestamp("created_at_source")).thenReturn(Timestamp.valueOf(createdAt));
    when(rs.getTimestamp("updated_at_source")).thenReturn(Timestamp.valueOf(createdAt.plusDays(resolutionDays)));
    when(rs.getTimestamp("closed_at_source")).thenReturn(null);
    return rs;
  }

  private ResultSet moduleCatalogRow(String modules) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("module_names")).thenReturn(modules);
    return rs;
  }

  private ResultSet personOptionsRow() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("author_names")).thenReturn("author");
    when(rs.getString("assignee_names")).thenReturn("assignee");
    return rs;
  }
}
