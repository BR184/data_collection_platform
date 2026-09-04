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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/** 客户问题延期议题看板金标测试：重构安全网（docs/plans/statistics-board-framework-refactor.md）。 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueDelayIssuesBoardGoldenMasterTest
    extends AbstractStatisticBoardGoldenMasterTest {

  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R2");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R2"))
        .thenReturn(List.of("CC2026 R2"));
    when(milestoneCatalogService.matches("CC2026R2", "CC2026 R2")).thenReturn(true);
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-delay-issues", "test", "test", "test", Map.of(), null, null);
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
                  mapper.mapRow(issue(1101, "P1", true, true), 0),
                  mapper.mapRow(issue(1102, "P3", true, true), 1),
                  mapper.mapRow(issue(1103, "", true, true), 2));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
    doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("coalesce(module_names")) {
                RowMapper<Object> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(moduleCatalogRow("装配"), 0));
              }
              return List.of();
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
    return Path.of("src", "test", "resources", "golden", "customer-issue-delay-issues");
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
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"装配\"}]}"),
        Map.entry("priority-eq-p1",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"priorityLevel\",\"operator\":\"eq\",\"value\":\"P1\"}]}"),
        Map.entry("priority-ne-p1",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"priorityLevel\",\"operator\":\"ne\",\"value\":\"P1\"}]}"),
        Map.entry("milestone-label-group",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"milestoneTitle\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":3,\"labelGroupName\":\"里程碑\",\"operator\":\"intersects\",\"values\":[\"CC2026R2\"]}]}"),
        Map.entry("issue-state-eq-opened",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"issueState\",\"operator\":\"eq\",\"value\":\"opened\"}]}"),
        Map.entry("or-combo",
            "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"priorityLevel\",\"operator\":\"eq\",\"value\":\"P1\"},{\"fieldKey\":\"priorityLevel\",\"operator\":\"eq\",\"value\":\"P3\"}]}"));
  }

  @Override
  protected AbstractStatisticBoardService service() {
    return new CustomerIssueDelayIssuesBoardService(
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

  private ResultSet issue(int iid, String priorityLevel, boolean responseDelayed, boolean resolveDelayed)
      throws Exception {
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
    when(rs.getString("priority_level")).thenReturn(priorityLevel);
    when(rs.getString("bug_status")).thenReturn("处理中");
    when(rs.getString("category")).thenReturn("功能");
    when(rs.getString("milestone_title")).thenReturn("CC2026 R2");
    when(rs.getString("author_name")).thenReturn("author");
    when(rs.getString("assignee_name")).thenReturn("assignee");
    when(rs.getString("module_names")).thenReturn("装配");
    when(rs.getString("label_names")).thenReturn(priorityLevel);
    when(rs.getBoolean("is_excluded")).thenReturn(false);
    when(rs.getBoolean("delay_issue")).thenReturn(false);
    return rs;
  }

  private ResultSet moduleCatalogRow(String modules) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("module_names")).thenReturn(modules);
    return rs;
  }
}
