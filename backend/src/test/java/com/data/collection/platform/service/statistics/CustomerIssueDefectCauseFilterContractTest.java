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
import com.data.collection.platform.entity.statistics.StatisticFilterField;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
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
import java.util.function.Function;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectCauseFilterContractTest {
  @org.junit.jupiter.api.BeforeEach
  void bindReadDependencyFixtures() {
    // 公式单测复用同一数据替身；实际修订隔离由真库读取测试覆盖。
    org.mockito.Mockito.lenient().when(milestoneCatalogService.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class))).thenReturn(milestoneCatalogService);
    org.mockito.Mockito.lenient().when(snapshotRequestFactory.forRead(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class))).thenReturn(snapshotRequestFactory);
  }


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
            "customer-issue-defect-cause", "test", "test", StatisticBoardTestSnapshotScopes.defaultProjectScopes(), Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(invocation -> ((Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse>)
                invocation.getArgument(2))
            .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
        .when(snapshotService)
        .readOrRefresh(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), any(), any());
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(6);
              return List.of(
                  mapper.mapRow(row(2101, "工程图", "新增需求"), 0),
                  mapper.mapRow(row(2102, "草图", "新增需求"), 1));
            })
        .when(issueFactQueryService)
        .query(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), anyString(), any(RowMapper.class));

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
            "customer-issue-defect-cause", "test", "test", StatisticBoardTestSnapshotScopes.defaultProjectScopes(), Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(invocation -> ((Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse>)
                invocation.getArgument(2))
            .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
        .when(snapshotService)
        .readOrRefresh(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), any(), any());
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(6);
              return List.of(
                  mapper.mapRow(row(2201, "工程图", "新增需求", "历史遗留、申请延期"), 0),
                  mapper.mapRow(row(2202, "草图", "新增需求", "已修复/完成"), 1));
            })
        .when(issueFactQueryService)
        .query(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), anyString(), any(RowMapper.class));

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

  @Test
  @SuppressWarnings("unchecked")
  void test_personQuickFields_loadBoardOffersFactCandidatesAndKeepsTextOperators() throws Exception {
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of("CC2026 R3"));
    StatisticBoardSnapshotService.SnapshotRequest snapshotRequest =
        new StatisticBoardSnapshotService.SnapshotRequest(
            "customer-issue-defect-cause", "test", "test", StatisticBoardTestSnapshotScopes.defaultProjectScopes(), Map.of(), null, null);
    when(snapshotRequestFactory.issueRequest(
            anyString(), anyString(), anyString(), anyLong(), any(), anyString(), anyMap(), any(), any()))
        .thenReturn(snapshotRequest);
    doAnswer(invocation -> ((Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse>)
                invocation.getArgument(2))
            .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
        .when(snapshotService)
        .readOrRefresh(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), any(), any());
    ResultSet first = row(2301, "工程图", "新增需求");
    ResultSet second = row(2302, "草图", "新增需求");
    when(first.getString("author_name")).thenReturn("张三");
    when(second.getString("author_name")).thenReturn("张三");
    when(first.getString("assignee_name")).thenReturn("李四");
    when(second.getString("assignee_name")).thenReturn("  ");
    doAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(6);
              return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
            })
        .when(issueFactQueryService)
        .query(org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.read.FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), anyString(), any(RowMapper.class));

    CustomerIssueDefectCauseBoardService service = service();
    StatisticFilterField author = quickPersonField(service.loadBoard(Map.of()), "authorName");
    StatisticFilterField assignee = quickPersonField(service.loadBoard(Map.of()), "assigneeName");

    // 人员字段保持 text 与其关系清单，候选只补同范围事实取值：去空、按来源顺序去重。
    assertThat(author.type()).isEqualTo("text");
    assertThat(author.operators()).containsExactly("eq", "ne", "contains", "isEmpty", "isNotEmpty");
    assertThat(author.options())
        .extracting(StatisticFilterOption::label, StatisticFilterOption::value)
        .containsExactly(Tuple.tuple("张三", "张三"));
    assertThat(assignee.options())
        .extracting(StatisticFilterOption::label, StatisticFilterOption::value)
        .containsExactly(Tuple.tuple("李四", "李四"));

    // 选中候选后仍走原条件求值路径。
    assertThat(moduleRows(service.loadBoard(Map.of(
            "filterGroup",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"authorName\",\"operator\":\"eq\",\"value\":\"张三\"}]}"))))
        .containsExactlyInAnyOrder("工程图", "草图");
    assertThat(moduleRows(service.loadBoard(Map.of(
            "filterGroup",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"assigneeName\",\"operator\":\"eq\",\"value\":\"王五\"}]}"))))
        .isEmpty();
  }

  private static StatisticFilterField quickPersonField(StatisticBoardResponse response, String key) {
    return response.definition().filters().stream()
        .filter(field -> key.equals(field.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("缺少人员快速字段: " + key));
  }

  private static List<String> moduleRows(StatisticBoardResponse response) {
    return response.rows().stream()
        .filter(row -> !row.rowKey().startsWith("__"))
        .map(row -> row.rowLabel())
        .toList();
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
        snapshotRequestFactory).forRead(com.data.collection.platform.service.read.TestReadSelections.all());
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
