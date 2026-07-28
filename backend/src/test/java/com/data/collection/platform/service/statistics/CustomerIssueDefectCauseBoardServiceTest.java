package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.RealtimeIncrementalRefreshService;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectCauseBoardServiceTest {

  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  @Test
  @SuppressWarnings("unchecked")
  void shouldBuildTotalAndRatioFromVisibleModuleRows() throws Exception {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026R3"));
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    when(milestoneCatalogService.matches("CC2026R3", "CC2026 R3")).thenReturn(true);
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-defect-cause", "test", "test", "test", Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(
            invocation ->
                ((Supplier<StatisticBoardResponse>) invocation.getArgument(1)).get())
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

    CustomerIssueDefectCauseBoardService service =
        new CustomerIssueDefectCauseBoardService(
            new JsonUtils(new ObjectMapper()),
            realtimeWorkspaceService,
            realtimeIncrementalRefreshService,
            issueFactQueryService,
            new CustomerIssueScopeProfile(),
            issueLinkSupport,
            milestoneCatalogService,
            snapshotService,
            snapshotRequestFactory);

    StatisticBoardResponse response = service.loadBoard(Map.of());

    StatisticRowData total = row(response, "__total__");
    StatisticRowData ratio = row(response, "__ratio__");
    assertThat(cell(total, "demand_misunderstand").numericValue()).isZero();
    assertThat(cell(total, "add_demand_2").numericValue()).isEqualTo(2);
    assertThat(cell(total, "precondition_data_exception").numericValue()).isEqualTo(2);
    assertThat(cell(total, "prompt_message").numericValue()).isZero();
    assertThat(cell(total, "logic_integration_interface_error").numericValue()).isZero();
    assertThat(cell(total, "add_demand_2").drilldown()).isFalse();
    assertThat(cell(ratio, "add_demand_2").displayValue()).isEqualTo("50.00%");
    assertThat(cell(ratio, "precondition_data_exception").displayValue()).isEqualTo("50.00%");
    assertThat(cell(total, "add_demand_2").numericValue())
        .isEqualTo(moduleRowSum(response, "add_demand_2"));
    assertThat(cell(total, "precondition_data_exception").numericValue())
        .isEqualTo(moduleRowSum(response, "precondition_data_exception"));
    verify(snapshotRequestFactory)
        .issueRequest(
            eq("customer-issue-defect-cause"),
            eq("customer-issue-defect-cause@2026-07-28-v9"),
            anyString(),
            anyMap(),
            any(),
            any());
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

  private long moduleRowSum(StatisticBoardResponse response, String columnKey) {
    return response.rows().stream()
        .filter(row -> !row.rowKey().startsWith("__"))
        .mapToLong(row -> cell(row, columnKey).numericValue())
        .sum();
  }
}
