package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class StatisticExplicitModuleFilterSupport {
  private StatisticExplicitModuleFilterSupport() {}

  static boolean matchesExplicitModuleFilter(String moduleName, StatisticFilterGroup filterGroup) {
    List<StatisticFilterCondition> conditions = explicitModuleConditions(filterGroup);
    if (conditions.isEmpty()) {
      return true;
    }
    return conditions.stream().allMatch(condition -> matches(moduleName, condition));
  }

  private static List<StatisticFilterCondition> explicitModuleConditions(StatisticFilterGroup filterGroup) {
    if (filterGroup == null
        || filterGroup.conditions() == null
        || filterGroup.conditions().isEmpty()
        || "OR".equalsIgnoreCase(filterGroup.logic())) {
      return List.of();
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && "moduleName".equals(condition.fieldKey()))
        .filter(StatisticExplicitModuleFilterSupport::isPositiveModuleCondition)
        .toList();
  }

  private static boolean isPositiveModuleCondition(StatisticFilterCondition condition) {
    String operator = condition.operator();
    return "eq".equals(operator)
        || "contains".equals(operator)
        || "partialContainsAny".equals(operator)
        || condition.usesLabelGroup();
  }

  private static boolean matches(String moduleName, StatisticFilterCondition condition) {
    String actual = normalize(moduleName);
    if (!StringUtils.hasText(actual)) {
      return false;
    }
    List<String> expectedValues =
        condition.usesLabelGroup()
            ? (condition.values() == null ? List.of() : condition.values())
            : List.of(condition.value());
    if (expectedValues.stream().noneMatch(StringUtils::hasText)) {
      return true;
    }
    String operator = condition.operator();
    return expectedValues.stream()
        .filter(StringUtils::hasText)
        .map(StatisticExplicitModuleFilterSupport::normalize)
        .anyMatch(
            expected -> {
              if ("contains".equals(operator) || "partialContainsAny".equals(operator)) {
                return actual.contains(expected);
              }
              return actual.equals(expected);
            });
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
  }
}
