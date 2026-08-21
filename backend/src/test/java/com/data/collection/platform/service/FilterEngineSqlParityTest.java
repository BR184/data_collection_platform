package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.statistics.engine.StatisticFieldDescriptor;
import com.data.collection.platform.service.statistics.engine.StatisticFilterEngine;
import com.data.collection.platform.service.IssueStatusMembers;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL↔Engine 平价契约测试（docs/plans/statistics-board-framework-refactor.md 收尾项）：
 * 同一批 issue_fact 行、同一组筛选条件，分别经 IssueFactFilterGroupSqlSupport 的 SQL 谓词
 * 与 StatisticFilterEngine 内存谓词求值，断言命中集合一致。任何一侧语义漂移立即失败。
 */
@SpringBootTest
class FilterEngineSqlParityTest {

  private static final long IID_FLOOR = 99100L;
  private static final long IID_CEIL = 99999L;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 引擎侧行模型：直接映射 issue_fact 列，字段键与 SQL 支持层对齐。 */
  private record ParityRow(
      long iid,
      String projectName,
      List<String> moduleNames,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String issueState,
      List<String> labels,
      LocalDateTime createdAt) {}

  private List<ParityRow> rows;

  @BeforeEach
  void cleanAndInsert() {
    jdbcTemplate.update("delete from issue_fact where issue_iid between ? and ?", IID_FLOOR, IID_CEIL);
    insertRow(99101, "CC_Product", "装配 & 钣金", "一级缺陷", "P1", "已修复/完成", "opened",
        "状态：已修复/完成,优先级：P1", LocalDateTime.of(2026, 6, 10, 9, 0));
    insertRow(99102, "CC_Product", "喷涂", "二级缺陷", "P3", "处理中", "opened",
        "状态：处理中", LocalDateTime.of(2026, 7, 20, 14, 30));
    insertRow(99103, "Other", "装配", "一级缺陷", "P2", "已修复/完成", "closed",
        "状态：已修复/完成", LocalDateTime.of(2026, 8, 5, 16, 0));
    rows = loadRows();
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from issue_fact where issue_iid between ? and ?", IID_FLOOR, IID_CEIL);
  }

  private void insertRow(
      long iid, String project, String modules, String severity, String priority,
      String bugStatus, String state, String labels, LocalDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into issue_fact(
          source_system, source_instance, project_id, project_name, issue_id, issue_iid, title,
          issue_state, milestone_title, author_name, assignee_name, created_at_source, updated_at_source,
          module_names, testing_phase, severity_level, priority_level, bug_status, category,
          label_names, deleted
        ) values ('GITLAB', 'default', 325, ?, ?, ?, ?, ?, 'CC2026R3', 'author', 'assignee', ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
        """,
        project, iid, iid, "issue " + iid, state,
        createdAt, createdAt.plusHours(1), modules, "", severity, priority, bugStatus,
        "缺陷", labels);
  }

  private List<ParityRow> loadRows() {
    return jdbcTemplate.query(
        "select issue_iid, project_name, module_names, severity_level, priority_level, bug_status,"
            + " issue_state, label_names, created_at_source from issue_fact"
            + " where issue_iid between ? and ? order by issue_iid",
        (rs, rowNum) -> new ParityRow(
            rs.getLong("issue_iid"),
            rs.getString("project_name"),
            splitTrim(rs.getString("module_names"), "&"),
            rs.getString("severity_level"),
            rs.getString("priority_level"),
            rs.getString("bug_status"),
            rs.getString("issue_state"),
            splitTrim(rs.getString("label_names"), ","),
            rs.getTimestamp("created_at_source").toLocalDateTime()),
        IID_FLOOR, IID_CEIL);
  }

  private static List<String> splitTrim(String raw, String separator) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return List.of(raw.split("\\s*" + java.util.regex.Pattern.quote(separator) + "\\s*")).stream()
        .map(v -> v.isBlank() ? null : v)
        .filter(Objects::nonNull)
        .toList();
  }

  private Map<String, StatisticFieldDescriptor<ParityRow>> fields() {
    return Map.ofEntries(
        Map.entry("projectName", StatisticFieldDescriptor.<ParityRow>multiValue(
            "projectName", row -> List.of(Objects.toString(row.projectName(), "")))),
        Map.entry("moduleName", StatisticFieldDescriptor.<ParityRow>multiValue(
            "moduleName", ParityRow::moduleNames)),
        Map.entry("severityLevel", StatisticFieldDescriptor.<ParityRow>multiValue(
            "severityLevel", row -> List.of(Objects.toString(row.severityLevel(), "")))),
        Map.entry("priorityLevel", StatisticFieldDescriptor.<ParityRow>multiValue(
            "priorityLevel", row -> List.of(Objects.toString(row.priorityLevel(), "")))),
        Map.entry("bugStatus", StatisticFieldDescriptor.<ParityRow>multiValueWithOverride(
            "bugStatus",
            row -> IssueStatusMembers.parse(row.bugStatus()),
            (row, condition) -> condition.usesLabelGroup()
                ? IssueStatusMembers.matchesLabelGroup(
                    row.bugStatus(), condition.operator(), condition.values())
                : IssueStatusMembers.matchesFilter(
                    row.bugStatus(), condition.operator(), trimToNull(condition.value())))),
        Map.entry("issueState", StatisticFieldDescriptor.<ParityRow>multiValue(
            "issueState", row -> List.of(Objects.toString(row.issueState(), "")))),
        Map.entry("labels", StatisticFieldDescriptor.<ParityRow>multiValue(
            "labels", ParityRow::labels)),
        Map.entry("createdAt", StatisticFieldDescriptor.<ParityRow>dateTime(
            "createdAt", ParityRow::createdAt)));
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private List<Long> sqlIds(StatisticFilterGroup group) {
    SqlPredicate predicate =
        IssueFactFilterGroupSqlSupport.toSql(group, false)
            .orElseThrow(() -> new IllegalStateException("SQL 侧未生成谓词"));
    List<Object> args = new ArrayList<>(List.of(IID_FLOOR, IID_CEIL));
    args.addAll(predicate.args());
    return jdbcTemplate.query(
        "select issue_iid from issue_fact where deleted = false"
            + " and issue_iid between ? and ? and ("
            + predicate.predicate()
            + ") order by issue_iid",
        (rs, rowNum) -> rs.getLong("issue_iid"),
        args.toArray());
  }

  private List<Long> engineIds(StatisticFilterGroup group) {
    Predicate<ParityRow> predicate = StatisticFilterEngine.compile(group, fields());
    return rows.stream().filter(predicate).map(ParityRow::iid).sorted().toList();
  }

  private void assertParity(String caseId, StatisticFilterGroup group) {
    List<Long> sql = sqlIds(group);
    List<Long> engine = engineIds(group);
    assertThat(engine).as("平价失败 @%s", caseId).containsExactlyElementsOf(sql);
  }

  private static StatisticFilterCondition literal(String fieldKey, String operator, String value) {
    return new StatisticFilterCondition(fieldKey, operator, value, null);
  }

  private static StatisticFilterCondition labelGroup(
      String fieldKey, String operator, List<String> values) {
    return new StatisticFilterCondition(
        fieldKey, operator, null, null, "LABEL_GROUP", 1L, "组", values);
  }

  @Test
  void test_text_operator_parity_on_project_name() {
    // 仅覆盖双路径均显式支持的操作符；textCondition 不含 contains（走索引搜索列）。
    assertParity("eq-hit", new StatisticFilterGroup("AND", List.of(literal("projectName", "eq", "CC_Product"))));
    assertParity("eq-miss", new StatisticFilterGroup("AND", List.of(literal("projectName", "eq", "Other"))));
    assertParity("ne", new StatisticFilterGroup("AND", List.of(literal("projectName", "ne", "CC_Product"))));
    assertParity("isNotEmpty", new StatisticFilterGroup("AND", List.of(literal("projectName", "isNotEmpty", null))));
  }

  @Test
  void test_member_parity_on_bug_status_and_priority() {
    assertParity("bug-status-plain-ne",
        new StatisticFilterGroup("AND", List.of(literal("bugStatus", "ne", "处理中"))));
    assertParity("bug-status-label-group",
        new StatisticFilterGroup("AND", List.of(labelGroup("bugStatus", "intersects", List.of("已修复/完成")))));
    assertParity("priority-ne-p3",
        new StatisticFilterGroup("AND", List.of(literal("priorityLevel", "ne", "P3"))));
  }

  @Test
  void test_datetime_and_combo_parity() {
    assertParity("created-between",
        new StatisticFilterGroup("AND", List.of(new StatisticFilterCondition(
            "createdAt", "between", "2026-07-01T00:00:00", "2026-07-31T23:59:59"))));
    // 已知休眠分歧（见计划文档）：year/month 操作符裸值格式两轨解析不同，
    // 看板过滤器实际发送完整时间戳，不进入本平价矩阵。
    assertParity("or-combo",
        new StatisticFilterGroup("OR", List.of(
            literal("severityLevel", "eq", "一级缺陷"),
            literal("priorityLevel", "eq", "P3"))));
  }
}
