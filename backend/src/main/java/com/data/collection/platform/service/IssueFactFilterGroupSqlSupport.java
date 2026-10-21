package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * issue_fact 的条件表达式 SQL 编译器：把 {@link StatisticFilterGroup} 编译为一段绑定参数的 WHERE 谓词。
 *
 * <p>操作符语义以 {@code StatisticFilterEngine} 为唯一权威，本类只做同语义的 SQL 表达；两者平价由
 * {@code IssueFactFilterGroupSqlSupportTest} 与看板筛选契约测试锁定。字段绑定与 {@code StatisticFieldDescriptor}
 * 的物理列同源，新增已声明字段必须在此补齐，不允许调用方另写私有操作符实现。
 *
 * <p>只有全部条件都能编译时才返回谓词：任一条件无法下推则返回空，调用方必须显式决定失败或改走内存求值，
 * 不得静默丢弃条件。
 */
public final class IssueFactFilterGroupSqlSupport {
  private IssueFactFilterGroupSqlSupport() {}

  static Optional<SqlPredicate> toSql(StatisticFilterGroup filterGroup) {
    return buildSql(filterGroup, false, false);
  }

  static Optional<SqlPredicate> toSql(StatisticFilterGroup filterGroup, boolean useFullTestingPhase) {
    return buildSql(filterGroup, useFullTestingPhase, false);
  }

  /**
   * 客户问题范围的条件下推。
   *
   * <p>测试状态与延期原因按客户问题的完整事实列（{@code testing_phase}、事实侧 {@code delay_cause}）
   * 求值；里程碑别名与项目/排除等公共范围不属于本方法职责，调用方应继续用公共范围谓词与之 AND 收口。
   *
   * @param filterGroup 已展开的用户条件组；空组返回空谓词
   * @return 可下推的谓词与按序绑定参数；存在无法编译的条件时返回空
   */
  public static Optional<SqlPredicate> toCustomerIssueSql(StatisticFilterGroup filterGroup) {
    return buildSql(filterGroup, true, true);
  }

  private static Optional<SqlPredicate> buildSql(
      StatisticFilterGroup filterGroup, boolean useFullTestingPhase, boolean useFactDelayCause) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return Optional.of(new SqlPredicate("", List.of()));
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      Optional<SqlPredicate> conditionSql =
          conditionToSql(condition, useFullTestingPhase, useFactDelayCause);
      if (conditionSql.isEmpty()) {
        return Optional.empty();
      }
      predicates.add("(" + conditionSql.get().predicate() + ")");
      args.addAll(conditionSql.get().args());
    }
    String joiner = "OR".equalsIgnoreCase(filterGroup.logic()) ? " or " : " and ";
    return Optional.of(new SqlPredicate(String.join(joiner, predicates), args));
  }

  private static Optional<SqlPredicate> conditionToSql(
      StatisticFilterCondition condition,
      boolean useFullTestingPhase,
      boolean useFactDelayCause) {
    if (condition == null) {
      return Optional.empty();
    }
    return switch (condition.fieldKey()) {
      case "keyword" -> keywordCondition(condition);
      case "issueIid" -> issueIidCondition(condition);
      case "title" -> titleCondition(condition);
      case "customerName" -> IssueCustomerMembershipSqlSupport.condition(condition);
      case "projectName" -> searchableTextCondition("project_name", condition);
      case "moduleName" -> moduleCondition(condition);
      case "functionName" -> searchableTextCondition("function_name", condition);
      case "testingPhase" -> phaseCondition(condition, useFullTestingPhase);
      case "reasonCategory" -> textCondition("reason_category", condition);
      case "fixUser" -> searchableTextCondition("fix_user", condition);
      case "delayCause" -> useFactDelayCause
          ? Optional.of(IssueDelayCauseMemberSqlSupport.condition("delay_cause", condition))
          : Optional.empty();
      case "illegalReason" -> illegalReasonCondition(condition);
      case "severityLevel" -> textCondition("severity_level", condition);
      case "priorityLevel" -> textCondition("priority_level", condition);
      case "issueState" -> textCondition("issue_state", condition);
      case "bugStatus" -> bugStatusCondition(condition);
      case "category" -> textCondition("category", condition);
      case "milestoneTitle" -> milestoneCondition(condition);
      case "authorName" -> authorCondition(condition);
      case "handlerName" -> handlerCondition(condition);
      case "assigneeName" -> assigneeCondition(condition);
      case "createdAt" -> dateTimeCondition("created_at_source", condition);
      case "updatedAt" -> dateTimeCondition("updated_at_source", condition);
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> searchableTextCondition(
      String column, StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return containsTextCondition(column, condition);
    }
    return textCondition(column, condition);
  }

  private static Optional<SqlPredicate> keywordCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "contains", "notContains" ->
          indexedSearchCondition(
              List.of("search_text", "search_compact", "search_spell", "search_initials"),
              condition);
      case "isEmpty" -> Optional.of(falsePredicate());
      case "isNotEmpty" -> Optional.of(truePredicate());
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> titleCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "title_search_text",
              "title_search_compact",
              "title_search_spell",
              "title_search_initials"),
          condition);
    }
    return textCondition("title", condition);
  }

  /**
   * 模块条件的 SQL 表达。
   *
   * <p>等值/不等是“完整成员比较”，不是子串匹配：模块多值列按看板聚合与明细同源的 {@code [,&]} 拆分并 trim
   * 后逐成员比较，因此 {@code 工具&装配}、{@code 工具 , 装配} 都命中成员“工具”；空白条件值与引擎一致
   * 恒不命中（等值为假、不等为真）。包含/不包含仍走模块搜索索引列，保持拼音与首字母检索语义。
   */
  private static Optional<SqlPredicate> moduleCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "eq" -> Optional.of(moduleMemberCondition(condition.value(), false));
      case "ne" -> Optional.of(moduleMemberCondition(condition.value(), true));
      case "contains", "notContains" ->
          indexedSearchCondition(
              List.of(
                  "module_search_text",
                  "module_search_compact",
                  "module_search_spell",
                  "module_search_initials"),
              condition);
      case "isEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(module_names, '')), '') is null", List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(module_names, '')), '') is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static SqlPredicate moduleMemberCondition(String value, boolean negate) {
    String expected = lower(value);
    if (expected.isEmpty()) {
      return negate ? truePredicate() : falsePredicate();
    }
    String predicate =
        "exists (select 1 from regexp_split_to_table(coalesce(module_names, ''), '\\s*[,&]\\s*')"
            + " as filter_modules(module_name)"
            + " where nullif(btrim(filter_modules.module_name), '') is not null"
            + " and lower(btrim(filter_modules.module_name)) = ?)";
    return new SqlPredicate(
        negate ? "not (" + predicate + ")" : predicate, List.of(expected));
  }

  private static Optional<SqlPredicate> phaseCondition(
      StatisticFilterCondition condition, boolean useFullTestingPhase) {
    if (CustomerIssueTestingPhaseSupport.isUnspecifiedFilter(condition.value())) {
      return unspecifiedTestingPhaseCondition(condition);
    }
    if ("RESOLVED_LITERAL_SET".equalsIgnoreCase(condition.valueType())) {
      return resolvedLiteralSetCondition(useFullTestingPhase ? "testing_phase" : "phase_filter_value", condition);
    }
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      if (useFullTestingPhase) {
        return containsTextCondition("testing_phase", condition);
      }
      return indexedSearchCondition(
          List.of("phase_search_text", "phase_search_compact", "phase_search_spell", "phase_search_initials"),
          condition);
    }
    return textCondition(useFullTestingPhase ? "testing_phase" : "phase_filter_value", condition);
  }

  private static Optional<SqlPredicate> unspecifiedTestingPhaseCondition(
      StatisticFilterCondition condition) {
    String emptyPredicate = "nullif(btrim(coalesce(testing_phase, '')), '') is null";
    return switch (condition.operator()) {
      case "eq", "contains" -> Optional.of(new SqlPredicate(emptyPredicate, List.of()));
      case "ne", "notContains" -> Optional.of(new SqlPredicate("not (" + emptyPredicate + ")", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> resolvedLiteralSetCondition(
      String column, StatisticFilterCondition condition) {
    List<String> values =
        condition.values() == null
            ? List.of()
            : condition.values().stream()
                .map(TextQuerySupport::trimToNull)
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    if (values.isEmpty()) {
      return Optional.of("ne".equals(condition.operator()) ? truePredicate() : falsePredicate());
    }
    String placeholders = String.join(",", values.stream().map(ignored -> "?").toList());
    String predicate = "lower(coalesce(" + column + ", '')) in (" + placeholders + ")";
    if ("ne".equals(condition.operator())) {
      predicate = "not (" + predicate + ")";
    }
    return Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)));
  }

  private static Optional<SqlPredicate> milestoneCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "milestone_search_text",
              "milestone_search_compact",
              "milestone_search_spell",
              "milestone_search_initials"),
          condition);
    }
    return textCondition("milestone_title", condition);
  }

  private static Optional<SqlPredicate> authorCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of("author_search_text", "author_search_compact", "author_search_spell", "author_search_initials"),
          condition);
    }
    return textCondition("author_name", condition);
  }

  private static Optional<SqlPredicate> handlerCondition(StatisticFilterCondition condition) {
    return searchableTextCondition("handler_name", condition);
  }

  private static Optional<SqlPredicate> assigneeCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "assignee_search_text",
              "assignee_search_compact",
              "assignee_search_spell",
              "assignee_search_initials"),
          condition);
    }
    return textCondition("assignee_name", condition);
  }

  private static Optional<SqlPredicate> illegalReasonCondition(StatisticFilterCondition condition) {
    String operator = condition.operator();
    if ("eq".equals(operator) || "ne".equals(operator)) {
      List<String> rawReasons = SystemTestIllegalReasonSupport.rawReasonsFor(condition.value());
      if (rawReasons.isEmpty()) {
        return textCondition("illegal_reason", condition);
      }
      List<String> predicates = new ArrayList<>();
      List<Object> args = new ArrayList<>();
      for (String rawReason : rawReasons) {
        predicates.add(
            "lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') like ?");
        args.add("%," + lower(rawReason) + ",%");
      }
      String predicate = String.join(" or ", predicates);
      if ("ne".equals(operator)) {
        predicate = "not (" + predicate + ")";
      }
      return Optional.of(new SqlPredicate(predicate, args));
    }
    return textCondition("illegal_reason", condition);
  }

  private static Optional<SqlPredicate> indexedSearchCondition(
      List<String> columns, StatisticFilterCondition condition) {
    List<String> candidates = FactSearchIndexSupport.keywordCandidates(condition.value());
    if (candidates.isEmpty()) {
      return Optional.of(truePredicate());
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String candidate : candidates) {
      String pattern = "%" + LabelGroupFilterOperatorSupport.escapeLikePattern(candidate) + "%";
      for (String column : columns) {
        predicates.add(column + " like ? escape '\\'");
        args.add(pattern);
      }
    }
    String predicate = "(" + String.join(" or ", predicates) + ")";
    if ("notContains".equals(condition.operator())) {
      predicate = "not " + predicate;
    }
    return Optional.of(new SqlPredicate(predicate, args));
  }

  private static Optional<SqlPredicate> textCondition(
      String column, StatisticFilterCondition condition) {
    if (condition.usesLabelGroup()) {
      return labelGroupTextCondition(column, condition);
    }
    return switch (condition.operator()) {
      case "eq" -> Optional.of(
          new SqlPredicate("lower(coalesce(" + column + ", '')) = ?", List.of(lower(condition.value()))));
      case "ne" -> Optional.of(
          new SqlPredicate("lower(coalesce(" + column + ", '')) <> ?", List.of(lower(condition.value()))));
      case "isEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(" + column + ", '')), '') is null", List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(" + column + ", '')), '') is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> bugStatusCondition(StatisticFilterCondition condition) {
    if (condition.usesLabelGroup()) {
      return Optional.of(
          switch (condition.operator()) {
            case "partialContainsAny" ->
                IssueStatusMemberSqlSupport.matchesPartialAny(condition.values());
            case "notIntersects" -> IssueStatusMemberSqlSupport.notMatchesAny(condition.values());
            case "containsAll" -> IssueStatusMemberSqlSupport.matchesAll(condition.values());
            case "notContainsAll" -> IssueStatusMemberSqlSupport.notMatchesAll(condition.values());
            default -> IssueStatusMemberSqlSupport.matchesAny(condition.values());
          });
    }
    return Optional.of(
        switch (condition.operator()) {
          case "eq" -> IssueStatusMemberSqlSupport.matches(condition.value());
          case "ne" -> IssueStatusMemberSqlSupport.notMatches(condition.value());
          case "isEmpty" -> IssueStatusMemberSqlSupport.isEmpty();
          case "isNotEmpty" -> IssueStatusMemberSqlSupport.isNotEmpty();
          default -> falsePredicate();
        });
  }

  private static Optional<SqlPredicate> labelGroupTextCondition(
      String column, StatisticFilterCondition condition) {
    if (LabelGroupFilterOperatorSupport.isPartialContainsAny(condition.operator())) {
      return partialContainsAnyCondition(column, condition);
    }
    List<String> values = normalizedLabelGroupValues(condition);
    if (values.isEmpty()) {
      return Optional.of(falsePredicate());
    }
    String placeholders = String.join(",", values.stream().map(ignored -> "?").toList());
    String predicate = "lower(coalesce(" + column + ", '')) in (" + placeholders + ")";
    return switch (condition.operator()) {
      case "notIntersects", "notContainsAll" -> Optional.of(new SqlPredicate("not (" + predicate + ")", new ArrayList<>(values)));
      case "containsAll" -> values.size() == 1
          ? Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)))
          : Optional.of(falsePredicate());
      default -> Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)));
    };
  }

  private static Optional<SqlPredicate> partialContainsAnyCondition(
      String column, StatisticFilterCondition condition) {
    List<String> patterns = normalizedLabelGroupValues(condition).stream()
        .map(LabelGroupFilterOperatorSupport::likeContainsPattern)
        .filter(value -> value != null)
        .toList();
    if (patterns.isEmpty()) {
      return Optional.of(falsePredicate());
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String pattern : patterns) {
      predicates.add("lower(coalesce(" + column + ", '')) like ? escape '\\'");
      args.add(pattern);
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private static List<String> normalizedLabelGroupValues(StatisticFilterCondition condition) {
    if (condition.values() == null) {
      return List.of();
    }
    return condition.values().stream()
        .map(TextQuerySupport::trimToNull)
        .filter(value -> value != null)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  /**
   * 字面子串匹配的 SQL 表达：条件值先转义 {@code %}、{@code _} 与转义符本身，再声明 {@code escape '\'}。
   *
   * <p>与 {@code StatisticFilterEngine.contains} 一致：空白条件值不命中任何行（“包含空值”在引擎里恒假），
   * 因此不能退化成 {@code like '%%'} 这类全表匹配。
   */
  private static Optional<SqlPredicate> containsTextCondition(
      String column, StatisticFilterCondition condition) {
    String pattern = LabelGroupFilterOperatorSupport.likeContainsPattern(condition.value());
    if (pattern == null) {
      return Optional.of(
          "notContains".equals(condition.operator()) ? truePredicate() : falsePredicate());
    }
    return likeCondition(column, pattern, "notContains".equals(condition.operator()));
  }

  private static Optional<SqlPredicate> likeCondition(
      String column, String pattern, boolean negate) {
    String predicate = "lower(coalesce(" + column + ", '')) like ? escape '\\'";
    return Optional.of(
        new SqlPredicate(
            negate ? "not (" + predicate + ")" : predicate, List.of(pattern)));
  }

  private static Optional<SqlPredicate> issueIidCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "contains" -> {
        String literal = LabelGroupFilterOperatorSupport.escapeLikePattern(condition.value());
        yield literal == null
            ? Optional.of(falsePredicate())
            : Optional.of(
                new SqlPredicate(
                    "cast(issue_iid as varchar) like ? escape '\\'", List.of("%" + literal + "%")));
      }
      case "eq" -> Optional.of(new SqlPredicate("issue_iid = ?", List.of(parseLong(condition.value()))));
      case "ne" -> Optional.of(new SqlPredicate("issue_iid <> ?", List.of(parseLong(condition.value()))));
      case "isEmpty" -> Optional.of(falsePredicate());
      case "isNotEmpty" -> Optional.of(truePredicate());
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> dateTimeCondition(
      String column, StatisticFilterCondition condition) {
    String operator = condition.operator();
    return switch (operator) {
      case "year" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY') = ?", List.of(condition.value())));
      case "month" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM') = ?", List.of(condition.value())));
      case "day" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM-DD') = ?", List.of(condition.value())));
      case "before" -> Optional.of(new SqlPredicate(column + " < ?", List.of(parseDateTime(condition.value()))));
      case "after" -> Optional.of(new SqlPredicate(column + " > ?", List.of(parseDateTime(condition.value()))));
      case "between" -> Optional.of(
          new SqlPredicate(
              column + " between ? and ?",
              List.of(parseDateTime(condition.value()), parseDateTime(condition.secondaryValue()))));
      case "isEmpty" -> Optional.of(new SqlPredicate(column + " is null", List.of()));
      case "isNotEmpty" -> Optional.of(new SqlPredicate(column + " is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static SqlPredicate truePredicate() {
    return new SqlPredicate("1 = 1", List.of());
  }

  private static SqlPredicate falsePredicate() {
    return new SqlPredicate("1 = 0", List.of());
  }

  private static String lower(String value) {
    return TextQuerySupport.normalizeDisplay(value).toLowerCase(Locale.ROOT);
  }

  private static Long parseLong(String value) {
    try {
      return Long.parseLong(TextQuerySupport.normalizeDisplay(value));
    } catch (NumberFormatException exception) {
      return Long.MIN_VALUE;
    }
  }

  private static LocalDateTime parseDateTime(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return LocalDateTime.MIN;
    }
    try {
      if (normalized.length() <= 10) {
        return LocalDate.parse(normalized.substring(0, Math.min(normalized.length(), 10))).atStartOfDay();
      }
      return LocalDateTime.parse(normalized.replace(' ', 'T'));
    } catch (RuntimeException exception) {
      return LocalDateTime.MIN;
    }
  }
}
