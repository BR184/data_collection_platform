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
class CustomerIssueDefectCauseFilterContractTest {

  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  @Test
  void shouldExposeCustomerIssueFieldsWithoutLegacyTestingPhase() {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026 R3"));

    assertThat(service().getDefinition().filters())
        .extracting("key")
        .containsExactly(
            "milestoneTitle",
            "projectName",
            "moduleName",
            "issueIid",
            "title",
            "severityLevel",
            "bugStatus",
            "category",
            "issueState",
            "authorName",
            "assigneeName")
        .doesNotContain("testingPhase");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldApplyModuleConditionToBoardRows() throws Exception {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026 R3"));
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
                  mapper.mapRow(row(2101, "工程图", "新增需求"), 0),
                  mapper.mapRow(row(2102, "草图", "新增需求"), 1));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));

    StatisticBoardResponse response =
        service()
            .loadBoard(
                Map.of(
                    "filterGroup",
                    """
                    {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","value":"工程图"}]}
                    """));

    assertThat(response.rows().stream()
            .filter(row -> !row.rowKey().startsWith("__"))
            .map(row -> row.rowLabel()))
        .containsExactly("工程图");
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldMatchCombinedBugStatusByIndependentMember() throws Exception {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026 R3"));
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
                  mapper.mapRow(row(2201, "工程图", "新增需求", "历史遗留、申请延期"), 0),
                  mapper.mapRow(row(2202, "草图", "新增需求", "已修复/完成"), 1));
            })
        .when(issueFactQueryService)
        .query(anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));

    StatisticBoardResponse response =
        service()
            .loadBoard(
                Map.of(
                    "filterGroup",
                    """
                    {"logic":"AND","conditions":[{"fieldKey":"bugStatus","operator":"eq","value":"申请延期"}]}
                    """));

    assertThat(response.rows().stream()
            .filter(row -> !row.rowKey().startsWith("__"))
            .map(row -> row.rowLabel()))
        .containsExactly("工程图");
  }

  private CustomerIssueDefectCauseBoardService service() {
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
    return row(iid, modules, reason, "已修复/完成");
  }

  private ResultSet row(int iid, String modules, String reason, String bugStatus) throws Exception {
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
    when(rs.getTimestamp("created_at"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 6, 1, 9, 0)));
    when(rs.getTimestamp("updated_at"))
        .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 7, 6, 9, 0)));
    when(rs.getTimestamp("closed_at")).thenReturn(null);
    when(rs.getString("issue_state")).thenReturn("opened");
    when(rs.getString("bug_status")).thenReturn(bugStatus);
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
