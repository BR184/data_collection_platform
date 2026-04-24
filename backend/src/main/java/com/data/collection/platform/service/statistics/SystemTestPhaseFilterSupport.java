package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class SystemTestPhaseFilterSupport {
  private static final String TESTING_PHASE_FIELD = "testingPhase";

  private SystemTestPhaseFilterSupport() {}

  static boolean matches(SystemTestPhaseFilterSource source, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(source, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  static String selectedTestingPhase(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return null;
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && TESTING_PHASE_FIELD.equals(condition.fieldKey()))
        .map(StatisticFilterCondition::value)
        .map(SystemTestPhaseFilterSupport::trimToNull)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  private static boolean matchesCondition(
      SystemTestPhaseFilterSource source, StatisticFilterCondition condition) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if (!TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
      return true;
    }
    String candidate = trimToEmpty(source.phaseFilterValue());
    String value = trimToNull(condition.value());
    return switch (condition.operator()) {
      case "eq" -> value == null || candidate.equalsIgnoreCase(value);
      case "ne" -> value == null || !candidate.equalsIgnoreCase(value);
      case "contains" ->
          value == null || candidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
      case "isEmpty" -> !StringUtils.hasText(candidate);
      case "isNotEmpty" -> StringUtils.hasText(candidate);
      default -> true;
    };
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
}
