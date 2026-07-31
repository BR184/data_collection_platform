package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** 系统测试阶段目录成员与事实字段之间的唯一匹配策略，供所有系统测试口径统计复用。 */
public final class SystemTestPhaseMembershipPolicy {
  private static final String TESTING_PHASE_FIELD = "testingPhase";
  private static final SqlPredicate EMPTY_PREDICATE = new SqlPredicate("", List.of());

  private SystemTestPhaseMembershipPolicy() {}

  public enum MatchMode {
    CONTAINS_MEMBER,
    EXACT_MEMBER
  }

  static SqlPredicate sqlPredicate(
      StatisticFilterGroup filterGroup,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      MatchMode mode) {
    String selectedPhase = selectedTestingPhaseForSql(filterGroup);
    if (selectedPhase == null || phaseScopeResolver == null) {
      return EMPTY_PREDICATE;
    }
    return sqlPredicate(
        resolvedMembers(
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
            selectedPhase,
            phaseScopeResolver),
        mode);
  }

  /**
   * 为默认 {@code testing_phase} 列构造阶段成员谓词。
   *
   * @param members 阶段目录展开后的成员
   * @param mode 成员匹配方式
   * @return 参数化 SQL 片段及其参数
   */
  public static SqlPredicate sqlPredicate(List<String> members, MatchMode mode) {
    return sqlPredicate("testing_phase", members, mode);
  }

  /**
   * 为调用方指定的可信列表达式构造阶段成员谓词。
   *
   * @param columnExpression 由服务端代码提供的列名或带别名列名，不接受请求参数
   * @param members 阶段目录展开后的成员
   * @param mode 成员匹配方式
   * @return 参数化 SQL 片段及其参数
   */
  public static SqlPredicate sqlPredicate(
      String columnExpression, List<String> members, MatchMode mode) {
    List<String> normalizedMembers = normalizeMembers(members);
    if (normalizedMembers.isEmpty()) {
      return EMPTY_PREDICATE;
    }
    List<String> clauses = new ArrayList<>(normalizedMembers.size());
    List<Object> args = new ArrayList<>(normalizedMembers.size());
    for (String member : normalizedMembers) {
      if (mode == MatchMode.CONTAINS_MEMBER) {
        clauses.add(columnExpression + " like ?");
        args.add("%" + member + "%");
      } else {
        clauses.add(columnExpression + " = ?");
        args.add(member);
      }
    }
    return new SqlPredicate(String.join(" or ", clauses), List.copyOf(args));
  }

  static Membership membership(
      StatisticFilterGroup filterGroup,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      MatchMode mode) {
    if (phaseScopeResolver == null) {
      return new Membership(filterGroup, mode, false, List.of(), Map.of());
    }
    Map<String, List<String>> resolvedBySelection = new LinkedHashMap<>();
    Set<String> configuredMembers = new LinkedHashSet<>();
    for (String parent : phaseScopeResolver.listEnabledParentNames(
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID)) {
      configuredMembers.addAll(resolveOnce(parent, phaseScopeResolver, resolvedBySelection));
    }
    if (filterGroup != null && filterGroup.conditions() != null) {
      for (StatisticFilterCondition condition : filterGroup.conditions()) {
        if (condition != null
            && TESTING_PHASE_FIELD.equals(condition.fieldKey())
            && ("eq".equals(condition.operator()) || "ne".equals(condition.operator()))) {
          String selected = trimToNull(condition.value());
          if (selected != null) {
            resolveOnce(selected, phaseScopeResolver, resolvedBySelection);
          }
        }
      }
    }
    if (configuredMembers.isEmpty()) {
      configuredMembers.addAll(resolvedBySelection.getOrDefault(
          selectedTestingPhase(filterGroup), List.of()));
    }
    return new Membership(
        filterGroup,
        mode,
        true,
        List.copyOf(configuredMembers),
        Map.copyOf(resolvedBySelection));
  }

  static String selectedTestingPhase(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return null;
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && TESTING_PHASE_FIELD.equals(condition.fieldKey()))
        .map(StatisticFilterCondition::value)
        .map(SystemTestPhaseMembershipPolicy::trimToNull)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  static String containsPrefilterValue(String selectedPhase, List<String> resolvedMembers) {
    String selected = trimToNull(selectedPhase);
    List<String> members = normalizeMembers(resolvedMembers);
    if (selected == null
        || members.isEmpty()
        || members.stream().anyMatch(member -> !member.contains(selected))) {
      return null;
    }
    return selected;
  }

  public static boolean matches(
      String actualTestingPhase, List<String> members, MatchMode mode) {
    String actual = trimToNull(actualTestingPhase);
    if (actual == null) {
      return false;
    }
    return normalizeMembers(members).stream()
        .anyMatch(member ->
            mode == MatchMode.CONTAINS_MEMBER ? actual.contains(member) : actual.equals(member));
  }

  private static boolean matchesCondition(
      SystemTestPhaseFilterSource source,
      StatisticFilterCondition condition,
      Map<String, List<String>> resolvedBySelection,
      boolean hasPhaseResolver,
      MatchMode mode) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if (!TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
      return true;
    }
    String parentCandidate = trimToEmpty(source.phaseFilterValue());
    String phaseCandidate = trimToEmpty(source.phaseLabel());
    String value = trimToNull(condition.value());
    if (hasPhaseResolver
        && ("eq".equals(condition.operator()) || "ne".equals(condition.operator()))) {
      boolean matched = value == null || matches(
          phaseCandidate,
          resolvedBySelection.getOrDefault(value, List.of()),
          mode);
      return "ne".equals(condition.operator()) ? !matched : matched;
    }
    return switch (condition.operator()) {
      case "eq" ->
          value == null
              || parentCandidate.equalsIgnoreCase(value)
              || phaseCandidate.equalsIgnoreCase(value);
      case "ne" ->
          value == null
              || (!parentCandidate.equalsIgnoreCase(value)
                  && !phaseCandidate.equalsIgnoreCase(value));
      case "contains" ->
          value == null
              || containsIgnoreCase(parentCandidate, value)
              || containsIgnoreCase(phaseCandidate, value);
      case "isEmpty" -> !StringUtils.hasText(parentCandidate) && !StringUtils.hasText(phaseCandidate);
      case "isNotEmpty" -> StringUtils.hasText(parentCandidate) || StringUtils.hasText(phaseCandidate);
      default -> true;
    };
  }

  private static List<String> resolvedMembers(
      Long projectId,
      String selectedPhase,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (phaseScopeResolver == null || !StringUtils.hasText(selectedPhase)) {
      return List.of();
    }
    return normalizeMembers(phaseScopeResolver.resolvePhases(projectId, selectedPhase));
  }

  private static List<String> resolveOnce(
      String selectedPhase,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      Map<String, List<String>> resolvedBySelection) {
    String normalized = trimToNull(selectedPhase);
    if (normalized == null) {
      return List.of();
    }
    return resolvedBySelection.computeIfAbsent(
        normalized,
        key -> resolvedMembers(
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
            key,
            phaseScopeResolver));
  }

  private static List<String> normalizeMembers(List<String> members) {
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    return members.stream()
        .map(SystemTestPhaseMembershipPolicy::trimToNull)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
  }

  private static String selectedTestingPhaseForSql(StatisticFilterGroup filterGroup) {
    if (filterGroup == null
        || "OR".equalsIgnoreCase(filterGroup.logic())
        || filterGroup.conditions() == null) {
      return null;
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null)
        .filter(condition -> TESTING_PHASE_FIELD.equals(condition.fieldKey()))
        .filter(condition -> "eq".equals(condition.operator()))
        .filter(condition -> !condition.usesLabelGroup())
        .map(StatisticFilterCondition::value)
        .map(SystemTestPhaseMembershipPolicy::trimToNull)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  private static boolean containsIgnoreCase(String candidate, String value) {
    return StringUtils.hasText(candidate)
        && StringUtils.hasText(value)
        && candidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
  }

  private static String trimToEmpty(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  public record SqlPredicate(String sql, List<Object> args) {
    public SqlPredicate {
      args = args == null ? List.of() : List.copyOf(args);
    }
  }

  record Membership(
      StatisticFilterGroup filterGroup,
      MatchMode mode,
      boolean enforceConfiguredScope,
      List<String> configuredMembers,
      Map<String, List<String>> resolvedBySelection) {
    Membership {
      configuredMembers = configuredMembers == null ? List.of() : List.copyOf(configuredMembers);
      resolvedBySelection =
          resolvedBySelection == null ? Map.of() : Map.copyOf(resolvedBySelection);
    }

    boolean matches(SystemTestPhaseFilterSource source) {
      if (source == null) {
        return false;
      }
      if (enforceConfiguredScope
          && !SystemTestPhaseMembershipPolicy.matches(
              source.phaseLabel(), configuredMembers, mode)) {
        return false;
      }
      if (filterGroup == null
          || filterGroup.conditions() == null
          || filterGroup.conditions().isEmpty()) {
        return true;
      }
      boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
      for (StatisticFilterCondition condition : filterGroup.conditions()) {
        boolean matched = matchesCondition(
            source, condition, resolvedBySelection, enforceConfiguredScope, mode);
        if (isOr && matched) {
          return true;
        }
        if (!isOr && !matched) {
          return false;
        }
      }
      return !isOr;
    }
  }
}
