package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@ExtendWith(MockitoExtension.class)
class CustomerIssueResponseEfficiencyBoardServiceTest {

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
  void shouldExpandAmpersandSeparatedModulesWhenAveragingResolutionCycle() {
    CustomerIssueResponseEfficiencyBoardService service = service();

    StatisticBoardResponse response = service.loadBoard(Map.of());

    StatisticRowData row = row(response, "二次开发");
    assertThat(cell(row, "resolution_cycle_days").displayValue()).isEqualTo("4.5");
  }

  @Test
  void shouldExpandAmpersandSeparatedModulesForResolutionDrilldown() {
    CustomerIssueResponseEfficiencyBoardService service = service();

    StatisticDetailResponse detail =
        service.loadDetail(
            new StatisticDetailRequest(
                "customer-issue-response-efficiency",
                "二次开发",
                "resolution_cycle_days",
                1,
                10,
                "iid",
                "ascending",
                Map.of()));

    assertThat(detail.total()).isEqualTo(2);
    assertThat(detail.records()).extracting(record -> record.get("title"))
        .containsExactly("issue 2601", "issue 2602");
  }

  private CustomerIssueResponseEfficiencyBoardService service() {
    return new CustomerIssueResponseEfficiencyBoardService(
        new JsonUtils(new ObjectMapper()),
        issueFactQueryService,
        new CustomerIssueScopeProfile(),
        issueLinkSupport,
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory);
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

  private StatisticRowData row(StatisticBoardResponse response, String rowKey) {
    return response.rows().stream()
        .filter(row -> rowKey.equals(row.rowKey()))
        .findFirst()
        .orElseThrow();
  }

  private StatisticCellData cell(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow();
  }
}
