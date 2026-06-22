package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class SystemTestPhaseFilterSupport {
  private static final String TESTING_PHASE_FIELD = "testingPhase";

  private SystemTestPhaseFilterSupport() {}

  static boolean matches(SystemTestPhaseFilterSource source, StatisticFilterGroup filterGroup) {
    return matches(source, filterGroup, null);
  }

  static boolean matches(
      SystemTestPhaseFilterSource source,
      StatisticFilterGroup filterGroup,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(source, condition, phaseScopeResolver);
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
      SystemTestPhaseFilterSource source,
      StatisticFilterCondition condition,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if (!TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
      return true;
    }
    String parentCandidate = trimToEmpty(source.phaseFilterValue());
    String phaseCandidate = trimToEmpty(source.phaseLabel());
    String value = trimToNull(condition.value());
    if (phaseScopeResolver != null && ("eq".equals(condition.operator()) || "ne".equals(condition.operator()))) {
      boolean matched = matchesResolvedPhase(phaseScopeResolver, phaseCandidate, value);
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
              || parentCandidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))
              || phaseCandidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
      case "isEmpty" -> !StringUtils.hasText(parentCandidate) && !StringUtils.hasText(phaseCandidate);
      case "isNotEmpty" -> StringUtils.hasText(parentCandidate) || StringUtils.hasText(phaseCandidate);
      default -> true;
    };
  }

  private static boolean matchesResolvedPhase(
      SystemTestPhaseScopeResolver phaseScopeResolver, String phaseCandidate, String value) {
    return value == null
        || (StringUtils.hasText(phaseCandidate)
            && phaseScopeResolver.matchesLegacyCrownCadPhase(phaseCandidate, value));
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
