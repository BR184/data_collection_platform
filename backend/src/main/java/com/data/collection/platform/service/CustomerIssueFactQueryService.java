package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * 客户问题窄事实读取入口，供客户问题统计页与 BI 客户问题页共同消费。
 *
 * <p>职责边界：只按来源资格、项目、创建范围、里程碑成员与有类型的客户/模块/功能选择读取当前事实，
 * 不套用整表业务排除（D/S/N/L/E 各自的排除由纯规则在计算阶段施加），不复制记录页分页查询。
 *
 * <p>输入都是显式值，不接收页面 DTO；里程碑成员由调用方通过
 * {@link CustomerIssueMilestoneCatalogService#resolveMilestoneValues(String)} 解析后传入。
 * 精确成员选择使用绑定参数，{@code %} 与 {@code _} 不参与通配。
 */
@Service
public class CustomerIssueFactQueryService {
  public static final long CC_PRODUCT_PROJECT_ID = 325L;
  public static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);

  private static final String FACT_SQL =
      """
      select
             issue_fact.source_system,
             issue_fact.source_instance,
             issue_fact.project_id,
             issue_fact.issue_id,
             issue_fact.issue_iid,
             coalesce(issue_fact.project_name, '') as project_name,
             coalesce(issue_fact.title, '') as title,
             coalesce(issue_fact.issue_state, 'opened') as issue_state,
             coalesce(issue_fact.milestone_title, '') as milestone_title,
             coalesce(issue_fact.author_name, '') as author_name,
             coalesce(issue_fact.assignee_name, '') as assignee_name,
             coalesce(issue_fact.severity_level, '') as severity_level,
             coalesce(issue_fact.priority_level, '') as priority_level,
             coalesce(issue_fact.bug_status, '') as bug_status,
             coalesce(issue_fact.category, '') as category,
             coalesce(issue_fact.reason_category, '') as reason_category,
             coalesce(issue_fact.module_names, '') as module_names,
             coalesce(issue_fact.function_name, '') as function_name,
             coalesce(issue_fact.delay_cause, '') as delay_cause,
             coalesce(issue_fact.delay_reason, '') as delay_reason,
             coalesce(issue_fact.exclusion_reason, '') as exclusion_reason,
             coalesce(issue_fact.label_names, '') as label_names,
             coalesce(issue_fact.illegal_reason, '') as illegal_reason,
             coalesce(issue_fact.illegal_reasons, '') as illegal_reasons,
             coalesce(issue_fact.is_excluded, false) as is_excluded,
             coalesce(issue_fact.delay_issue, false) as delay_issue,
             coalesce(issue_fact.is_response_delayed, false) as is_response_delayed,
             coalesce(issue_fact.is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(issue_fact.is_regression, false) as is_regression,
             coalesce(issue_fact.is_crash, false) as is_crash,
             coalesce(issue_fact.is_level1_other, false) as is_level1_other,
             issue_fact.created_at_source,
             issue_fact.updated_at_source,
             issue_fact.closed_at_source,
             issue_fact.research_template_time,
             issue_fact.fixed_label_time,
             issue_fact.is_customer_requirement,
             (select string_agg(distinct member.customer_name, E'\\n')
                from issue_fact_customer_members member
               where member.source_system = issue_fact.source_system
                 and member.source_instance = issue_fact.source_instance
                 and member.project_id = issue_fact.project_id
                 and member.issue_id = issue_fact.issue_id) as customer_names
        from issue_fact
       where issue_fact.deleted = false
      """;

  private static final RowMapper<CustomerIssueFact> ROW_MAPPER =
      CustomerIssueFactQueryService::mapRow;

  private final IssueFactQueryService issueFactQueryService;

  public CustomerIssueFactQueryService(IssueFactQueryService issueFactQueryService) {
    this.issueFactQueryService = issueFactQueryService;
  }

  /** 成员选择类型：全部、缺失、精确值。 */
  public enum SelectionKind {
    ALL,
    MISSING,
    VALUE
  }

  /** 有类型的成员选择；VALUE 之外的 kind 忽略 value。 */
  public record MemberSelection(SelectionKind kind, String value) {
    public static MemberSelection all() {
      return new MemberSelection(SelectionKind.ALL, null);
    }

    public static MemberSelection missing() {
      return new MemberSelection(SelectionKind.MISSING, null);
    }

    public static MemberSelection of(String value) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized == null) {
        throw new IllegalArgumentException("成员精确选择不能为空值");
      }
      return new MemberSelection(SelectionKind.VALUE, normalized);
    }
  }

  /**
   * 窄事实读取输入。
   *
   * @param sourceInstance 为空表示不限来源实例
   * @param milestoneValues 已解析的里程碑精确成员值；为空集合表示当前没有启用范围
   * @param customer 客户成员选择
   * @param module 模块成员选择
   * @param function 功能选择
   */
  public record FactScopeRequest(
      String sourceInstance,
      List<String> milestoneValues,
      MemberSelection customer,
      MemberSelection module,
      MemberSelection function) {

    public FactScopeRequest {
      milestoneValues = milestoneValues == null ? List.of() : List.copyOf(milestoneValues);
      customer = customer == null ? MemberSelection.all() : customer;
      module = module == null ? MemberSelection.all() : module;
      function = function == null ? MemberSelection.all() : function;
    }
  }

  /** 窄事实行：完整身份 + 当前来源字段 + 规范客户成员 + 规则输入。 */
  public record CustomerIssueFact(
      String sourceSystem,
      String sourceInstance,
      long projectId,
      long issueId,
      long issueIid,
      String projectName,
      String title,
      String issueState,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      String reasonCategory,
      List<String> moduleNames,
      String functionName,
      String delayCause,
      String delayReason,
      String exclusionReason,
      String labelNames,
      String illegalReason,
      String illegalReasons,
      boolean excluded,
      boolean delayIssue,
      boolean responseDelayed,
      boolean resolveDelayed,
      boolean regression,
      boolean crash,
      boolean level1Other,
      LocalDateTime createdAtSource,
      LocalDateTime updatedAtSource,
      LocalDateTime closedAtSource,
      LocalDateTime researchTemplateTime,
      LocalDateTime fixedLabelTime,
      Boolean customerRequirement,
      List<String> customerNames) {

    public CustomerIssueFact {
      customerNames = customerNames == null ? List.of() : List.copyOf(customerNames);
    }

    /** 议题完整身份；去重、成员连接与下钻一律以此为准。 */
    public String identityKey() {
      return sourceSystem + "|" + sourceInstance + "|" + projectId + "|" + issueId;
    }

    /** GitLab 议题是否处于关闭状态（优先级修复谓词 P 的输入之一）。 */
    public boolean closed() {
      return closedAtSource != null || "closed".equalsIgnoreCase(issueState);
    }

    public boolean open() {
      return !closed();
    }
  }

  /**
   * 读取限定范围内的事实行。
   *
   * @param request 窄事实读取输入
   * @return 当前范围内的事实行；同身份只返回一条数据库行
   */
  public List<CustomerIssueFact> load(FactScopeRequest request) {
    FactScopeRequest safeRequest = request == null
        ? new FactScopeRequest(null, List.of(), null, null, null)
        : request;
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder(FACT_SQL);
    sql.append(" and issue_fact.project_id = ?");
    args.add(CC_PRODUCT_PROJECT_ID);
    sql.append(" and (issue_fact.created_at_source is null or issue_fact.created_at_source >= ?)");
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
    appendSourceInstance(sql, args, safeRequest.sourceInstance());
    appendMilestones(sql, args, safeRequest.milestoneValues());
    appendCustomerSelection(sql, args, safeRequest.customer());
    appendModuleSelection(sql, args, safeRequest.module());
    appendFunctionSelection(sql, args, safeRequest.function());
    return issueFactQueryService.query(sql.toString(), args, ROW_MAPPER);
  }

  private static void appendSourceInstance(StringBuilder sql, List<Object> args, String sourceInstance) {
    if (TextQuerySupport.trimToNull(sourceInstance) == null) {
      return;
    }
    sql.append(" and issue_fact.source_instance = ?");
    args.add(GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
  }

  private static void appendMilestones(
      StringBuilder sql, List<Object> args, List<String> milestoneValues) {
    List<String> values = milestoneValues.stream()
        .map(TextQuerySupport::trimToNull)
        .filter(java.util.Objects::nonNull)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
    if (values.isEmpty()) {
      // 没有启用范围时不得静默放大到全部里程碑；用恒假条件返回空集合，由调用方报告目录状态。
      sql.append(" and false");
      return;
    }
    sql.append(" and lower(coalesce(issue_fact.milestone_title, '')) in (")
        .append(String.join(",", java.util.Collections.nCopies(values.size(), "?")))
        .append(")");
    args.addAll(values);
  }

  private static void appendCustomerSelection(
      StringBuilder sql, List<Object> args, MemberSelection selection) {
    if (selection.kind() == SelectionKind.ALL) {
      return;
    }
    String operator = selection.kind() == SelectionKind.MISSING ? "isEmpty" : "eq";
    StatisticFilterCondition condition =
        new StatisticFilterCondition("customerName", operator, selection.value(), null);
    Optional<SqlPredicate> predicate = IssueCustomerMembershipSqlSupport.condition(condition);
    predicate.ifPresent(
        value -> {
          sql.append(" and ").append(value.predicate());
          args.addAll(value.args());
        });
  }

  private static void appendModuleSelection(
      StringBuilder sql, List<Object> args, MemberSelection selection) {
    if (selection.kind() == SelectionKind.ALL) {
      return;
    }
    // 成员语义与 Java 侧 IssueModuleMembers 同源；MISSING 不能拼出未绑定参数的谓词。
    SqlPredicate predicate =
        selection.kind() == SelectionKind.MISSING
            ? IssueModuleMemberSqlSupport.isEmpty()
            : IssueModuleMemberSqlSupport.matches(selection.value());
    sql.append(" and ").append(predicate.predicate());
    args.addAll(predicate.args());
  }

  private static void appendFunctionSelection(
      StringBuilder sql, List<Object> args, MemberSelection selection) {
    if (selection.kind() == SelectionKind.ALL) {
      return;
    }
    // 与模块成员同一空白规则：只去空格的 btrim 会把制表符/换行留成"有值"，与 Java 侧判定分叉。
    String normalizedFunction =
        "regexp_replace(coalesce(issue_fact.function_name, ''), '"
            + IssueModuleMembers.SQL_TRIM_REGEX
            + "', '', 'g')";
    if (selection.kind() == SelectionKind.MISSING) {
      sql.append(" and ").append(normalizedFunction).append(" = ''");
      return;
    }
    sql.append(" and lower(").append(normalizedFunction).append(") = ?");
    args.add(selection.value().toLowerCase(Locale.ROOT));
  }

  private static CustomerIssueFact mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new CustomerIssueFact(
        resultSet.getString("source_system"),
        resultSet.getString("source_instance"),
        resultSet.getLong("project_id"),
        resultSet.getLong("issue_id"),
        resultSet.getLong("issue_iid"),
        resultSet.getString("project_name"),
        resultSet.getString("title"),
        resultSet.getString("issue_state"),
        resultSet.getString("milestone_title"),
        resultSet.getString("author_name"),
        resultSet.getString("assignee_name"),
        resultSet.getString("severity_level"),
        resultSet.getString("priority_level"),
        resultSet.getString("bug_status"),
        resultSet.getString("category"),
        resultSet.getString("reason_category"),
        splitModuleNames(resultSet.getString("module_names")),
        resultSet.getString("function_name"),
        resultSet.getString("delay_cause"),
        resultSet.getString("delay_reason"),
        resultSet.getString("exclusion_reason"),
        resultSet.getString("label_names"),
        resultSet.getString("illegal_reason"),
        resultSet.getString("illegal_reasons"),
        resultSet.getBoolean("is_excluded"),
        resultSet.getBoolean("delay_issue"),
        resultSet.getBoolean("is_response_delayed"),
        resultSet.getBoolean("is_resolve_delayed"),
        resultSet.getBoolean("is_regression"),
        resultSet.getBoolean("is_crash"),
        resultSet.getBoolean("is_level1_other"),
        toLocalDateTime(resultSet.getTimestamp("created_at_source")),
        toLocalDateTime(resultSet.getTimestamp("updated_at_source")),
        toLocalDateTime(resultSet.getTimestamp("closed_at_source")),
        toLocalDateTime(resultSet.getTimestamp("research_template_time")),
        toLocalDateTime(resultSet.getTimestamp("fixed_label_time")),
        nullableBoolean(resultSet, "is_customer_requirement"),
        readTextList(resultSet.getString("customer_names")));
  }

  private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
    boolean value = resultSet.getBoolean(column);
    return resultSet.wasNull() ? null : value;
  }

  private static List<String> splitModuleNames(String rawValue) {
    return IssueModuleMembers.split(rawValue);
  }

  private static List<String> readTextList(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : rawValue.split("\\R")) {
      String normalized = TextQuerySupport.trimToNull(part);
      if (normalized != null) {
        values.add(normalized);
      }
    }
    return List.copyOf(values);
  }
}
