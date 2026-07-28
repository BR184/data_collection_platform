package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
class CustomerIssueDelayIssuesBoardServiceTest {

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
            anyString(), anyString(), anyString(), anyMap(), any(), any()))
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
  void shouldExcludeBlankPriorityFromLegacyDelayTotalsAndDrilldown() {
    CustomerIssueDelayIssuesBoardService service = service();

    StatisticBoardResponse response = service.loadBoard(Map.of());
    StatisticRowData total = row(response, "__total__");
    StatisticRowData module = row(response, "装配");

    assertThat(cell(total, "resp_delay_p1").numericValue()).isEqualTo(1);
    assertThat(cell(total, "resp_delay_p3").numericValue()).isEqualTo(1);
    assertThat(cell(total, "resp_delay_sum").numericValue()).isEqualTo(2);
    assertThat(cell(total, "fix_delay_p3").numericValue()).isEqualTo(1);
    assertThat(cell(total, "fix_delay_sum").numericValue()).isEqualTo(2);
    assertThat(cell(module, "resp_delay_sum").numericValue()).isEqualTo(2);
    assertThat(cell(module, "fix_delay_sum").numericValue()).isEqualTo(2);

    StatisticDetailResponse detail =
        service.loadDetail(
            new StatisticDetailRequest(
                "customer-issue-delay-issues",
                "__total__",
                "resp_delay_sum",
                1,
                10,
                "",
                "ascending",
                Map.of()));

    assertThat(detail.total()).isEqualTo(2);
    assertThat(detail.records())
        .extracting(record -> record.get("priorityLevel"))
        .containsExactly("P1", "P3");
  }

  private CustomerIssueDelayIssuesBoardService service() {
    return new CustomerIssueDelayIssuesBoardService(
        new JsonUtils(new ObjectMapper()),
        issueFactQueryService,
        new CustomerIssueScopeProfile(),
        issueLinkSupport,
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory);
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
    when(rs.getBoolean("is_response_delayed")).thenReturn(responseDelayed);
    when(rs.getBoolean("is_resolve_delayed")).thenReturn(resolveDelayed);
    when(rs.getString("illegal_reason")).thenReturn("");
    when(rs.getString("illegal_reasons")).thenReturn("");
    when(rs.getTimestamp("created_at_source"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 4, 1, 9, 0)));
    when(rs.getTimestamp("updated_at_source"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 4, 2, 9, 0)));
    when(rs.getTimestamp("closed_at_source")).thenReturn(null);
    return rs;
  }

  private ResultSet moduleCatalogRow(String modules) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("module_names")).thenReturn(modules);
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
