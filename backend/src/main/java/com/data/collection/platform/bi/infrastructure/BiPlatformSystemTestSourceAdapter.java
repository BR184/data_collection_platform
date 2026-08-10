package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.BiSystemTestCauseClassifier;
import com.data.collection.platform.bi.domain.BiSystemTestDelayCauseClassifier;
import com.data.collection.platform.bi.domain.port.BiSystemTestSourcePort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import com.data.collection.platform.bi.domain.source.BiSystemTestSource;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.PageRecordSnapshotService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 从已发布议题事实和测试阶段目录读取 BI 系统测试基础度量。 */
public final class BiPlatformSystemTestSourceAdapter implements BiSystemTestSourcePort {
  private final JdbcTemplate jdbcTemplate;
  private final PageRecordSnapshotService snapshotService;
  private final BiSystemTestCauseClassifier causeClassifier;
  private final BiSystemTestDelayCauseClassifier delayCauseClassifier;

  public BiPlatformSystemTestSourceAdapter(
      JdbcTemplate jdbcTemplate,
      PageRecordSnapshotService snapshotService,
      BiSystemTestCauseClassifier causeClassifier,
      BiSystemTestDelayCauseClassifier delayCauseClassifier) {
    this.jdbcTemplate = jdbcTemplate;
    this.snapshotService = snapshotService;
    this.causeClassifier = causeClassifier;
    this.delayCauseClassifier = delayCauseClassifier;
  }

  @Override
  public BiSystemTestSource load(BiProductVersionScope scope) {
    // 阶段一：把产品版本目录中的测试阶段映射为稳定 ID，避免计算器依赖显示名称。
    String sourceVersion = sourceVersion(scope);
    Map<String, String> roundIdBySourceValue = new LinkedHashMap<>();
    List<BiSystemTestSource.RoundDefinition> rounds = scope.rounds().stream().map(round -> {
      roundIdBySourceValue.put(round.sourceValue(), round.id());
      return new BiSystemTestSource.RoundDefinition(round.id(), round.displayName(), round.sortOrder());
    }).toList();
    // 阶段二：只读取当前项目、有效且非建议类的已发布议题事实。
    List<IssueRow> rows = loadRows(scope.projectId(), new ArrayList<>(roundIdBySourceValue.keySet()));
    // 阶段三：完成字段归一化、原因分类和延期原因分类后交给领域计算器。
    List<BiSystemTestSource.IssueRecord> issues = rows.stream()
        .map(row -> new BiSystemTestSource.IssueRecord(
            row.issueId(),
            roundIdBySourceValue.get(row.testingPhase()),
            row.severity(),
            row.priority(),
            row.fixed(),
            modules(row.moduleNames()),
            BiSourceDimension.fromNullable(row.assignee(), "未指派"),
            causeClassifier.classify(row.reasonCategory(), row.labelNames()),
            row.delayed(),
            row.delayed()
                ? delayCauseClassifier.classify(
                    row.delayCause(), row.delayReason(), row.labelNames())
                : List.of()))
        .toList();
    // 阶段四：复核读取期间的事实版本，避免页面混用不同发布批次的数据。
    verifyStableVersion(sourceVersion, sourceVersion(scope));
    return new BiSystemTestSource(
        sourceVersion,
        sourceVersion,
        rounds,
        issues);
  }

  private List<IssueRow> loadRows(long projectId, List<String> testingPhases) {
    if (testingPhases.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(testingPhases.size(), "?"));
    String sql = """
        select issue_id,
               testing_phase,
               severity_level,
               priority_level,
               is_fixed,
               module_names,
               assignee_name,
               reason_category,
               label_names,
               delay_issue,
               delay_cause,
               delay_reason
          from issue_fact
         where source_instance = ?
           and project_id = ?
           and coalesce(deleted, false) = false
           and coalesce(is_excluded, false) = false
           and coalesce(severity_level, '') <> 'SUGGESTION'
           and coalesce(category, '') not like '%%建议%%'
           and coalesce(exclusion_reason, '') <> '建议'
           and testing_phase in (%s)
         order by issue_id asc
        """.formatted(placeholders);
    List<Object> args = new ArrayList<>();
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(projectId);
    args.addAll(testingPhases);
    return jdbcTemplate.query(sql, this::mapRow, args.toArray());
  }

  private IssueRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
    return new IssueRow(
        rs.getLong("issue_id"),
        rs.getString("testing_phase"),
        rs.getString("severity_level"),
        rs.getString("priority_level"),
        rs.getBoolean("is_fixed"),
        rs.getString("module_names"),
        rs.getString("assignee_name"),
        rs.getString("reason_category"),
        rs.getString("label_names"),
        rs.getBoolean("delay_issue"),
        rs.getString("delay_cause"),
        rs.getString("delay_reason"));
  }

  private List<BiSourceDimension> modules(String rawModuleNames) {
    if (rawModuleNames == null || rawModuleNames.isBlank()) {
      return List.of(BiSourceDimension.unknown("未标注模块"));
    }
    Set<BiSourceDimension> modules = new java.util.LinkedHashSet<>();
    for (String rawModuleName : rawModuleNames.split(",")) {
      if (rawModuleName != null && !rawModuleName.isBlank()) {
        modules.add(BiSourceDimension.identified(rawModuleName));
      }
    }
    return modules.isEmpty()
        ? List.of(BiSourceDimension.unknown("未标注模块"))
        : List.copyOf(modules);
  }

  private String sourceVersion(BiProductVersionScope scope) {
    return snapshotService.issueFactSourceVersion(
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        scope.projectId(),
        IssueScopeDimension.TESTING_PHASE,
        scope.businessKey());
  }

  private void verifyStableVersion(String before, String after) {
    if (!before.equals(after)) {
      throw new BiSourceVersionChangedException("system-test");
    }
  }

  private record IssueRow(
      long issueId,
      String testingPhase,
      String severity,
      String priority,
      boolean fixed,
      String moduleNames,
      String assignee,
      String reasonCategory,
      String labelNames,
      boolean delayed,
      String delayCause,
      String delayReason) {}
}
