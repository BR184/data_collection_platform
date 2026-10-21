package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.statistics.StatisticDetailColumn;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.RowMapper;

class CustomerIssueByFunctionBoardServiceTest {
  @Test
  void test_detailColumns_load_usesTestStatusTagsAndExpandedIssueState() {
    CustomerIssueByFunctionBoardService service = service(List.of());

    List<StatisticDetailColumn> columns = service.getDefinition().detailColumns();

    assertThat(columns.stream().filter(column -> !column.expandOnly()).map(StatisticDetailColumn::key))
        .containsExactly("iid", "moduleNames", "title", "functionName", "severityLevel", "priorityLevel", "bugStatus");
    assertThat(columns).anySatisfy(column -> {
      assertThat(column.key()).isEqualTo("bugStatus");
      assertThat(column.label()).isEqualTo("测试状态");
      assertThat(column.type()).isEqualTo("tags");
      assertThat(column.sortable()).isTrue();
      assertThat(column.expandOnly()).isFalse();
    });
    assertThat(columns).anySatisfy(column -> {
      assertThat(column.key()).isEqualTo("state");
      assertThat(column.label()).isEqualTo("议题状态");
      assertThat(column.expandOnly()).isTrue();
    });
  }

  @Test
  void test_openAndClosedIssues_load_preservesAllTestStatesWithoutFallback() throws Exception {
    String statuses = "未更新247 & 未更新客户测试环境 & 已修复/完成";
    CustomerIssueByFunctionBoardService service = service(List.of(
        issue(1, "opened", statuses), issue(2, "closed", statuses), issue(3, "closed", "")));

    StatisticDetailResponse detail = service.loadDetail(request("iid", "ascending"));

    assertThat(detail.total()).isEqualTo(3);
    assertThat(detail.records()).extracting(record -> record.get("bugStatus"))
        .containsExactly(statuses, statuses, "");
    assertThat(detail.records()).extracting(record -> record.get("state"))
        .containsExactly("未关闭", "已关闭", "已关闭");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ascending", "descending"})
  void test_testStatusSort_load_usesStatusTextAndStableIssueNumber(String order) throws Exception {
    CustomerIssueByFunctionBoardService service = service(List.of(
        issue(3, "opened", "新状态A"), issue(1, "closed", "新状态B"), issue(2, "closed", "新状态A")));

    StatisticDetailResponse detail = service.loadDetail(request("bugStatus", order));

    assertThat(detail.records()).extracting(record -> record.get("title"))
        .containsExactlyElementsOf("ascending".equals(order)
            ? List.of("议题2", "议题3", "议题1") : List.of("议题1", "议题3", "议题2"));
    assertThat(detail.sortField()).isEqualTo("bugStatus");
    assertThat(detail.sortOrder()).isEqualTo(order);
  }

  @Test
  @SuppressWarnings("unchecked")
  void test_declaredConditions_mainTableDetailAndPublicScope_shareOneCompiledPredicate() throws Exception {
    ScopeRecording recording = new ScopeRecording();
    CustomerIssueByFunctionBoardService service = recording.service();

    service.loadBoard(
        Map.of(
            "filterGroup",
                "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"severityLevel\",\"operator\":\"eq\","
                    + "\"value\":\"LEVEL1\"},{\"fieldKey\":\"functionName\",\"operator\":\"contains\","
                    + "\"value\":\"装配\"}]}",
            // 遗留的裸业务参数不得再作为第二路求值，否则 OR 条件会被二次 AND。
            "severityLevel", "LEVEL2"));
    service.loadDetail(
        new StatisticDetailRequest(
            "customer-issue-by-function", "__total__", "all", 1, 10, "iid", "ascending",
            Map.of("filterGroup",
                "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"severityLevel\",\"operator\":\"eq\","
                    + "\"value\":\"LEVEL1\"},{\"fieldKey\":\"functionName\",\"operator\":\"contains\","
                    + "\"value\":\"装配\"}]}")));

    assertThat(recording.predicates)
        .as("主表聚合与明细都必须带上同一份已下推条件")
        .hasSizeGreaterThanOrEqualTo(2)
        .allSatisfy(predicate -> assertThat(predicate)
            // OR 组必须整体加括号，否则 and/or 优先级会让右半分支绕过项目、里程碑与排除范围。
            // 文本包含条件必须带显式 ESCAPE，条件值里的 % 与 _ 才是字面文本而不是通配符。
            .contains(
                " and ((lower(coalesce(severity_level, '')) = ?) or (lower(coalesce(function_name, '')) like ? escape '\\'))")
            .contains("lower(coalesce(milestone_title, '')) in (?)"));
    assertThat(recording.args).containsSubsequence("cc2026 r3", "level1", "%装配%");
    assertThat(recording.filterMaps)
        .as("查询参数只保留公共范围，业务字段的收窄一律走条件表达式")
        .allSatisfy(filters -> assertThat(filters).doesNotContainKey("severityLevel"))
        .allSatisfy(filters -> assertThat(filters).containsEntry("projectId", "325"));
  }

  @Test
  void test_noDeclaredConditions_publicScopeStaysUntouchedByConditionFragments() throws Exception {
    ScopeRecording recording = new ScopeRecording();

    recording.service().loadBoard(Map.of());

    assertThat(recording.predicates)
        .as("无过滤时的范围与列分组保持原公共范围，不追加任何条件谓词")
        .isNotEmpty()
        .allSatisfy(predicate -> assertThat(predicate)
            .contains("(created_at_source is null or created_at_source >= ?)")
            .contains("coalesce(is_excluded, false) = false")
            .contains("lower(coalesce(milestone_title, '')) in (?)")
            .doesNotContain("severity_level")
            .doesNotContain("function_name = ?"));
    assertThat(recording.args).containsExactly(
        java.time.LocalDate.of(2026, 1, 1).atStartOfDay(), "cc2026 r3");
  }

  @Test
  @SuppressWarnings("unchecked")
  void test_ruleExplanationExpandStep_countsMatchMainTableModuleQualification() throws Exception {
    // 三条事实：无模块、以“未设定”开头的模块、两个有效模块；SQL 聚合同样只返回有效模块行。
    List<ResultSet> facts = List.of(
        issueWithModules(4101, "", "草图约束"),
        issueWithModules(4102, "未设定模块", "工程图"),
        issueWithModules(4103, "装配, 钣金", "装配工艺"));
    List<ResultSet> aggregate = List.of(
        moduleFunctionBucket("装配", "装配工艺", 1),
        moduleFunctionBucket("钣金", "装配工艺", 1));
    CustomerIssueByFunctionBoardService service = service(facts, aggregate);

    // 三条事实都带功能名，因此末步输入恒为 3 条议题；输出按模块贡献数，与主表同源。
    // 无正向模块条件：主表 2 行贡献，末步既不能算进两条无效模块占位，也不能与主表脱节。
    // 样本只能是实际产生贡献的 #4103，无模块的 #4101 与“未设定模块”的 #4102 不得出现在末步样本里。
    assertBoardMatchesExplanation(service, Map.of(), 2L, 3L, List.of("#4103 CC_Product"));
    // 正向模块条件：末步必须与主表一样收口到该模块行（不是议题数，也不回到原始展开量）。
    assertBoardMatchesExplanation(
        service,
        Map.of("filterGroup",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"eq\",\"value\":\"钣金\"}]}"),
        1L,
        3L,
        List.of("#4103 CC_Product"));
    // OR 条件不做正向模块折叠：两侧口径仍要一致，避免说明把 OR 误收口成单模块。
    assertBoardMatchesExplanation(
        service,
        Map.of("filterGroup",
            "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"eq\",\"value\":\"钣金\"},"
                + "{\"fieldKey\":\"severityLevel\",\"operator\":\"eq\",\"value\":\"LEVEL1\"}]}"),
        2L,
        3L,
        List.of("#4103 CC_Product"));
    // 零贡献：没有任何议题命中有效模块时，主表为空，末步数量与样本同时为空。
    assertBoardMatchesExplanation(
        service,
        Map.of("filterGroup",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"eq\",\"value\":\"不存在的模块\"}]}"),
        0L,
        3L,
        List.of());
  }

  /** 主表合计与规则说明末步必须同源；同时锁定末步输入仍是议题数、输出是模块贡献数、样本只含贡献议题。 */
  private void assertBoardMatchesExplanation(
      CustomerIssueByFunctionBoardService service, Map<String, String> filters, long expectedContributions,
      long expectedIssues, List<String> expectedSampleLabels) {
    long boardTotal = 0L;
    for (var row : service.loadBoard(filters).rows()) {
      for (var cell : row.cells()) {
        if (cell.columnKey().startsWith("func_count::")) {
          boardTotal += cell.numericValue() == null ? 0L : cell.numericValue();
        }
      }
    }
    assertThat(boardTotal).isEqualTo(expectedContributions);

    var flowSteps = service.getRuleExplanation(filters).flowSteps();
    var lastStep = flowSteps.get(flowSteps.size() - 1);
    assertThat(lastStep.key()).isEqualTo("module-function-expand");
    assertThat(lastStep.outputCount())
        .as("规则说明末步应等于主表合计（%s）", filters)
        .isEqualTo(expectedContributions);
    assertThat(lastStep.inputCount()).isEqualTo(expectedIssues);
    assertThat(lastStep.samples().stream().map(sample -> sample.label()).toList())
        .as("末步样本只列实际产生模块贡献的议题（%s）", filters)
        .containsExactlyElementsOf(expectedSampleLabels);
  }

  /** 记录按功能板下发到事实查询的谓词、参数与筛选图，用于锁定“条件只走一条下推路径”。 */
  private final class ScopeRecording {
    private final List<String> predicates = new ArrayList<>();
    private final List<Object> args = new ArrayList<>();
    private final List<Map<String, String>> filterMaps = new ArrayList<>();

    CustomerIssueByFunctionBoardService service() throws Exception {
      IssueFactQueryService queryService = mock(IssueFactQueryService.class);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                record(invocation);
                return List.of();
              })
          .when(queryService)
          .query(any(FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), anyString(),
              any(RowMapper.class));
      org.mockito.Mockito.doAnswer(
              invocation -> {
                record(invocation);
                RowMapper<Object> mapper = invocation.getArgument(5);
                return List.of(mapper.mapRow(issue(3101, "opened", "处理中"), 0));
              })
          .when(queryService)
          .query(any(FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class));
      CustomerIssueMilestoneCatalogService milestones = mock(CustomerIssueMilestoneCatalogService.class);
      FactReadSelection selection = mock(FactReadSelection.class);
      when(milestones.forRead(selection)).thenReturn(milestones);
      when(milestones.defaultMilestone()).thenReturn("CC2026R3");
      when(milestones.resolveMilestoneValues("CC2026R3")).thenReturn(List.of("CC2026 R3"));
      StatisticBoardSnapshotService snapshotService = mock(StatisticBoardSnapshotService.class);
      StatisticBoardSnapshotRequestFactory requestFactory = mock(StatisticBoardSnapshotRequestFactory.class);
      when(requestFactory.forRead(selection)).thenReturn(requestFactory);
      org.mockito.Mockito.doAnswer(
              invocation ->
                  ((java.util.function.Function<StatisticBoardSnapshotService.SourceRead,
                          com.data.collection.platform.entity.statistics.StatisticBoardResponse>)
                          invocation.getArgument(2))
                      .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
          .when(snapshotService)
          .readOrRefresh(any(FactReadSelection.class), any(), any());
      return new CustomerIssueByFunctionBoardService(
          new JsonUtils(new ObjectMapper()), queryService, new CustomerIssueScopeProfile(),
          mock(StatisticIssueLinkSupport.class), milestones, snapshotService, requestFactory).forRead(selection);
    }

    private void record(org.mockito.invocation.InvocationOnMock invocation) {
      predicates.add(invocation.getArgument(3));
      args.addAll((List<?>) invocation.getArgument(4));
      filterMaps.add(invocation.getArgument(2));
    }
  }

  private StatisticDetailRequest request(String sortField, String sortOrder) {
    return new StatisticDetailRequest("customer-issue-by-function", "__total__", "all", 1, 10,
        sortField, sortOrder, Map.of());
  }

  /** 同时提供事实行与 SQL 聚合行，用于比较主表与规则说明是否共用同一模块资格。 */
  @SuppressWarnings("unchecked")
  private CustomerIssueByFunctionBoardService service(List<ResultSet> facts, List<ResultSet> functionBuckets)
      throws Exception {
    IssueFactQueryService queryService = mock(IssueFactQueryService.class);
    when(queryService.query(any(FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class)))
        .thenAnswer(invocation -> mapAll(invocation.getArgument(5), facts));
    when(queryService.query(
            any(FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), anyString(), any(RowMapper.class)))
        .thenAnswer(invocation -> mapAll(invocation.getArgument(6), functionBuckets));
    CustomerIssueMilestoneCatalogService milestones = mock(CustomerIssueMilestoneCatalogService.class);
    FactReadSelection selection = mock(FactReadSelection.class);
    when(milestones.forRead(selection)).thenReturn(milestones);
    StatisticBoardSnapshotService snapshotService = mock(StatisticBoardSnapshotService.class);
    StatisticBoardSnapshotRequestFactory requestFactory = mock(StatisticBoardSnapshotRequestFactory.class);
    when(requestFactory.forRead(selection)).thenReturn(requestFactory);
    org.mockito.Mockito.doAnswer(
            invocation ->
                ((java.util.function.Function<StatisticBoardSnapshotService.SourceRead,
                        com.data.collection.platform.entity.statistics.StatisticBoardResponse>)
                        invocation.getArgument(2))
                    .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
        .when(snapshotService)
        .readOrRefresh(any(FactReadSelection.class), any(), any());
    return new CustomerIssueByFunctionBoardService(
        new JsonUtils(new ObjectMapper()), queryService, new CustomerIssueScopeProfile(),
        mock(StatisticIssueLinkSupport.class), milestones, snapshotService, requestFactory).forRead(selection);
  }

  private static List<Object> mapAll(RowMapper<?> mapper, List<ResultSet> rows) throws Exception {
    List<Object> mapped = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
      mapped.add(mapper.mapRow(rows.get(index), index));
    }
    return mapped;
  }

  private ResultSet issueWithModules(int iid, String moduleNames, String functionName) throws Exception {
    ResultSet row = mock(ResultSet.class);
    Map<String, String> values = Map.of("source_instance", "default", "project_name", "CC_Product",
        "title", "议题" + iid, "issue_state", "opened", "bug_status", "处理中",
        "module_names", moduleNames, "function_name", functionName, "milestone_title", "CC2026R3");
    when(row.getString(anyString())).thenAnswer(invocation -> values.getOrDefault(invocation.getArgument(0), ""));
    when(row.getLong("project_id")).thenReturn(325L);
    when(row.getLong("issue_id")).thenReturn((long) iid);
    when(row.getInt("issue_iid")).thenReturn(iid);
    Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 0, 0));
    when(row.getTimestamp("created_at_source")).thenReturn(created);
    when(row.getTimestamp("updated_at_source")).thenReturn(Timestamp.valueOf(created.toLocalDateTime().plusDays(iid)));
    return row;
  }

  /** 模拟 SQL GROUP BY 的一行：模块、功能与议题数（HAVING 已排除空模块与“未设定”开头）。 */
  private ResultSet moduleFunctionBucket(String moduleName, String functionName, long count) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString("module_name")).thenReturn(moduleName);
    when(row.getString("function_name")).thenReturn(functionName);
    when(row.getLong("issue_count")).thenReturn(count);
    return row;
  }

  private CustomerIssueByFunctionBoardService service(List<ResultSet> rows) {
    IssueFactQueryService queryService = mock(IssueFactQueryService.class);
    when(queryService.query(any(FactReadSelection.class), anyString(), anyMap(), anyString(), anyList(), any(RowMapper.class)))
        .thenAnswer(invocation -> {
          RowMapper<?> mapper = invocation.getArgument(5);
          List<Object> result = new ArrayList<>();
          for (int index = 0; index < rows.size(); index++) {
            result.add(mapper.mapRow(rows.get(index), index));
          }
          return result;
        });
    CustomerIssueMilestoneCatalogService milestones = mock(CustomerIssueMilestoneCatalogService.class);
    FactReadSelection selection = mock(FactReadSelection.class);
    when(milestones.forRead(selection)).thenReturn(milestones);
    return new CustomerIssueByFunctionBoardService(new JsonUtils(new ObjectMapper()), queryService,
        new CustomerIssueScopeProfile(), mock(StatisticIssueLinkSupport.class), milestones,
        mock(StatisticBoardSnapshotService.class), mock(StatisticBoardSnapshotRequestFactory.class))
        .forRead(selection);
  }

  private ResultSet issue(int iid, String state, String statuses) throws Exception {
    ResultSet row = mock(ResultSet.class);
    Map<String, String> values = Map.of("source_instance", "default", "project_name", "CC_Product",
        "title", "议题" + iid, "issue_state", state, "bug_status", statuses,
        "module_names", "工程图", "function_name", "工程图", "milestone_title", "CC2026R3");
    when(row.getString(anyString())).thenAnswer(invocation -> values.getOrDefault(invocation.getArgument(0), ""));
    when(row.getLong("project_id")).thenReturn(325L);
    when(row.getLong("issue_id")).thenReturn((long) iid);
    when(row.getInt("issue_iid")).thenReturn(iid);
    Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 0, 0));
    when(row.getTimestamp("created_at_source")).thenReturn(created);
    when(row.getTimestamp("updated_at_source")).thenReturn(Timestamp.valueOf(created.toLocalDateTime().plusDays(iid)));
    return row;
  }
}
