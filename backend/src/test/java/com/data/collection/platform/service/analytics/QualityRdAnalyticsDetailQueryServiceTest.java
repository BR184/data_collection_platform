package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 锁定研发质量看板下钻表的枚举中文展示：展示值走中文，筛选、排序与遗留状态判定仍走原始代码值。
 */
class QualityRdAnalyticsDetailQueryServiceTest {
  private static final String PROJECT_PHASE = "CC2026R4系统测试";

  private final FakeIssueJdbcTemplate jdbcTemplate = new FakeIssueJdbcTemplate();
  private final SystemTestPhaseScopeResolver phaseScopeResolver =
      mock(SystemTestPhaseScopeResolver.class);
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport =
      mock(QualityBoardCodeReviewReadSupport.class);
  private final QualityRdAnalyticsDetailQueryService service =
      new QualityRdAnalyticsDetailQueryService(jdbcTemplate, phaseScopeResolver, codeReviewReadSupport);

  QualityRdAnalyticsDetailQueryServiceTest() {
    when(phaseScopeResolver.resolvePhases(anyLong(), anyString())).thenReturn(List.of(PROJECT_PHASE));
  }

  @Test
  void releaseLeakageDrilldownShowsChineseLabelsWhileLeakageStateKeepsRawSemantics() {
    jdbcTemplate.rows.addAll(List.of(
        issue("1", "opened", "LEVEL1"),
        issue("2", "closed", "LEVEL2")));

    List<Map<String, Object>> records = service.load(
        "release-leakage-defects", context(Map.of("projectName", "CC2026R4"))).records();

    assertThat(records).hasSize(2);
    assertThat(records.get(0))
        .containsEntry("issueState", "未关闭")
        .containsEntry("severityLevel", "一级缺陷")
        .containsEntry("leakageState", "未关闭");
    assertThat(records.get(1))
        .containsEntry("issueState", "已关闭")
        .containsEntry("severityLevel", "二级缺陷")
        .containsEntry("leakageState", "已关闭");
  }

  @Test
  void fixUserDrilldownTranslatesDisplayButKeepsRawCodeInSqlFilters() {
    jdbcTemplate.rows.add(issue("7", "opened", "LEVEL1"));

    List<Map<String, Object>> records = service.load(
        "fix-user-defects",
        context(Map.of("projectName", "CC2026R4", "fixUser", "张三", "severityLevel", "LEVEL1")))
        .records();

    assertThat(records.get(0))
        .containsEntry("issueState", "未关闭")
        .containsEntry("severityLevel", "一级缺陷");
    assertThat(jdbcTemplate.sqlStatements.get(0))
        .contains("btrim(fix_user) = ?")
        .contains("btrim(severity_level) = ?");
    assertThat(jdbcTemplate.arguments.get(0)).contains("张三", "LEVEL1");
  }

  @Test
  void severitySortStillOrdersByRawCodeAfterDisplayTranslation() {
    jdbcTemplate.rows.addAll(List.of(
        issue("1", "closed", "LEVEL2"),
        issue("2", "closed", "LEVEL1"),
        issue("3", "closed", "LEVEL3")));

    List<Map<String, Object>> records = service.load(
        "fix-user-defects",
        new AnalyticsDashboardQueryContext(
            Map.of("projectName", "CC2026R4"), 1, 20, "severityLevel", "asc",
            AnalyticsDashboardQueryContext.ReadMode.FORMAL)).records();

    assertThat(records).extracting(row -> row.get("severityLevel"))
        .containsExactly("一级缺陷", "二级缺陷", "三级缺陷");
  }

  @Test
  void blankSeverityStaysBlankInsteadOfInventingPlaceholderText() {
    jdbcTemplate.rows.add(issue("4", "opened", ""));

    List<Map<String, Object>> records = service.load(
        "release-leakage-defects", context(Map.of("projectName", "CC2026R4"))).records();

    assertThat(records.get(0)).containsEntry("severityLevel", "");
  }

  @Test
  void codeReviewDrilldownTranslatesMergeRequestStateIgnoringCase() {
    when(codeReviewReadSupport.codeReviewRecordRows(anyString(), anyString(), any()))
        .thenReturn(List.of(
            codeReview("11", "MERGED"),
            codeReview("12", "open"),
            codeReview("13", "CLOSED"),
            codeReview("14", "APPROVED")));

    List<Map<String, Object>> records = service.load(
        "quality-code-review-records",
        context(Map.of("projectName", "CC2026R4", "source", "cc", "topic", "submission-frequency")))
        .records();

    assertThat(records).extracting(row -> row.get("mergeRequestState"))
        .containsExactly("APPROVED", "已关闭", "开放中", "已合并");
  }

  /** 未知状态不得被折算成“已关闭”：展示层原样透传，遗留率判据仍按 open/opened 之外即已关闭。 */
  @Test
  void unknownIssueStateIsPassedThroughInsteadOfBeingFoldedIntoClosed() {
    jdbcTemplate.rows.add(issue("5", "UNRECOGNIZED", "LEVEL1"));

    List<Map<String, Object>> records = service.load(
        "release-leakage-defects", context(Map.of("projectName", "CC2026R4"))).records();

    assertThat(records.get(0))
        .containsEntry("issueState", "UNRECOGNIZED")
        .containsEntry("leakageState", "已关闭");
  }

  @Test
  void knownIssueStatesMapToChineseRegardlessOfCase() {
    jdbcTemplate.rows.addAll(List.of(
        issue("6", "OPENED", "LEVEL1"),
        issue("7", "Closed", "LEVEL2"),
        issue("8", "open", "LEVEL3")));

    List<Map<String, Object>> records = service.load(
        "fix-user-defects", context(Map.of("projectName", "CC2026R4"))).records();

    assertThat(records).extracting(row -> row.get("issueState"))
        .containsExactly("未关闭", "已关闭", "未关闭");
    assertThat(records).extracting(row -> row.get("severityLevel"))
        .containsExactly("一级缺陷", "二级缺陷", "三级缺陷");
  }

  /** 数据库缺失该列值时保持 null，展示层不发明“未关闭/已关闭/未设定”任何文案。 */
  @Test
  void absentStateAndSeverityStayNullInsteadOfInventingLabels() {
    jdbcTemplate.rows.add(Map.of("issue_iid_column", "9", "module_name", "模块A"));

    List<Map<String, Object>> records = service.load(
        "release-leakage-defects", context(Map.of("projectName", "CC2026R4"))).records();

    assertThat(records.get(0))
        .containsEntry("issueState", null)
        .containsEntry("severityLevel", null);
  }

  @Test
  void pagingKeepsRawSeverityOrderAndTotal() {
    jdbcTemplate.rows.addAll(List.of(
        issue("21", "opened", "LEVEL3"),
        issue("22", "opened", "LEVEL1"),
        issue("23", "opened", "LEVEL2")));

    QualityRdAnalyticsDetailQueryService.DetailPage secondPage = service.load(
        "fix-user-defects",
        new AnalyticsDashboardQueryContext(
            Map.of("projectName", "CC2026R4"), 2, 1, "severityLevel", "asc",
            AnalyticsDashboardQueryContext.ReadMode.FORMAL));

    assertThat(secondPage.total()).isEqualTo(3);
    assertThat(secondPage.records()).hasSize(1);
    assertThat(secondPage.records().get(0)).containsEntry("severityLevel", "二级缺陷");
  }

  @Test
  void formalLowercaseMergeRequestStatesAreTranslated() {
    when(codeReviewReadSupport.codeReviewRecordRows(anyString(), anyString(), any()))
        .thenReturn(List.of(codeReview("31", "merged"), codeReview("32", "opened")));

    List<Map<String, Object>> records = service.load(
        "quality-code-review-records",
        context(Map.of("projectName", "CC2026R4", "source", "cc", "topic", "submission-frequency")))
        .records();

    assertThat(records).extracting(row -> row.get("mergeRequestState"))
        .containsExactly("开放中", "已合并");
  }

  private static AnalyticsDashboardQueryContext context(Map<String, String> parameters) {
    return new AnalyticsDashboardQueryContext(parameters, 1, 20, null, null,
        AnalyticsDashboardQueryContext.ReadMode.FORMAL);
  }

  private static Map<String, String> issue(String iid, String state, String severityLevel) {
    return Map.of(
        "issue_iid_column", iid,
        "issue_state", state,
        "severity_level", severityLevel,
        "module_name", "模块A",
        "assignee_name", "李四",
        "fix_user", "张三",
        "bug_status", "未修复",
        "category", "功能缺陷");
  }

  private static QualityBoardCodeReviewRecordExportRow codeReview(String iid, String state) {
    return new QualityBoardCodeReviewRecordExportRow(
        "cc", null, "CC2026R4", "模块A", state, Long.valueOf(iid), "标题" + iid, "张三",
        "王五", "李四", null, null, null, 100, 10, null, null, null, null, null,
        2, null, null, 20D, null, "dev", null, null, null, null, null, null, null, null,
        "crown-cad", null);
  }

  /** 只回放 {@code issueRows} 需要的列，其余列返回 null 由投影自身处理。 */
  private static final class FakeIssueJdbcTemplate extends JdbcTemplate {
    private final List<Map<String, String>> rows = new ArrayList<>();
    private final List<String> sqlStatements = new ArrayList<>();
    private final List<List<Object>> arguments = new ArrayList<>();

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      sqlStatements.add(sql);
      arguments.add(List.of(args));
      List<T> mapped = new ArrayList<>();
      for (int index = 0; index < rows.size(); index++) {
        Map<String, String> source = rows.get(index);
        ResultSet resultSet = mock(ResultSet.class);
        try {
          when(resultSet.getString(anyString())).thenAnswer(invocation ->
              source.get(invocation.getArgument(0, String.class)));
          when(resultSet.getObject("issue_iid")).thenAnswer(invocation ->
              source.get("issue_iid_column"));
          mapped.add(rowMapper.mapRow(resultSet, index));
        } catch (SQLException error) {
          throw new AssertionError(error);
        }
      }
      return mapped;
    }
  }
}
