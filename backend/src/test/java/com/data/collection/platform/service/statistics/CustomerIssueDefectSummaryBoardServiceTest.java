package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueFactRecordRepository;
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
class CustomerIssueDefectSummaryBoardServiceTest {

  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    when(milestoneCatalogService.matches("CC2026R3", "CC2026 R3")).thenReturn(true);
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);

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
              RowMapper<Object> mapper = invocation.getArgument(4);
              return List.of(mapper.mapRow(issue(), 0));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(2);
              return List.of(mapper.mapRow(moduleCatalogRow(), 0));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyList(), any(RowMapper.class));
  }

  @Test
  void test_stable_milestone_key_matches_exact_fact_member() {
    CustomerIssueDefectSummaryBoardService service =
        new CustomerIssueDefectSummaryBoardService(
            new JsonUtils(new ObjectMapper()),
            customerIssueScopeProfile,
            runtimeSupport,
            issueFactQueryService,
            issueLinkSupport,
            milestoneCatalogService,
            snapshotService,
            snapshotRequestFactory,
            issueFactRecordRepository);

    StatisticBoardResponse response = service.loadBoard(Map.of());

    StatisticRowData total = row(response, "__total__");
    assertThat(cell(total, "module_total").numericValue()).isEqualTo(1);
  }

  private ResultSet issue() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(1001L);
    when(rs.getInt("iid")).thenReturn(1001);
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
    when(rs.getString("issue_state")).thenReturn("opened");
    when(rs.getString("testing_phase")).thenReturn("");
    when(rs.getString("system_test_label")).thenReturn("");
    when(rs.getString("severity_level")).thenReturn("二级缺陷");
    when(rs.getString("priority_level")).thenReturn("P2");
    when(rs.getString("bug_status")).thenReturn("处理中");
    when(rs.getString("category")).thenReturn("缺陷");
    when(rs.getString("reason_category")).thenReturn("");
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
