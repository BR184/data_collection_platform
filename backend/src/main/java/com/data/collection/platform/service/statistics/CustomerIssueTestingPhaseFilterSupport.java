package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class CustomerIssueTestingPhaseFilterSupport {
  static final String TESTING_PHASE_FIELD = "testingPhase";

  private CustomerIssueTestingPhaseFilterSupport() {}

  static boolean matches(
      String milestoneTitle,
      String testingPhase,
      StatisticFilterGroup filterGroup,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    List<StatisticFilterCondition> phaseConditions =
        filterGroup.conditions().stream()
            .filter(condition -> condition != null && TESTING_PHASE_FIELD.equals(condition.fieldKey()))
            .toList();
    if (phaseConditions.isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : phaseConditions) {
      boolean matched = matchesCondition(milestoneTitle, testingPhase, condition, phaseScopeResolver);
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
      return "";
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && TESTING_PHASE_FIELD.equals(condition.fieldKey()))
        .filter(condition -> "eq".equals(condition.operator()))
        .map(StatisticFilterCondition::value)
        .map(TextQuerySupport::trimToNull)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("");
  }

  static StatisticFilterGroup removeTestingPhaseCondition(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return filterGroup;
    }
    List<StatisticFilterCondition> conditions =
        filterGroup.conditions().stream()
            .filter(condition -> condition == null || !TESTING_PHASE_FIELD.equals(condition.fieldKey()))
            .toList();
    return new StatisticFilterGroup(filterGroup.logic(), conditions);
  }

  private static boolean matchesCondition(
      String milestoneTitle,
      String testingPhase,
      StatisticFilterCondition condition,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    String value = TextQuerySupport.trimToNull(condition.value());
    String milestone = TextQuerySupport.trimToNull(milestoneTitle);
    String phase = TextQuerySupport.trimToNull(testingPhase);
    return switch (condition.operator()) {
      case "eq" -> value == null || equalsIgnoreCase(milestone, value) || matchesResolvedPhase(phase, value, phaseScopeResolver);
      case "ne" -> value == null && phase == null && milestone == null
          || (!equalsIgnoreCase(milestone, value) && !matchesResolvedPhase(phase, value, phaseScopeResolver));
      case "contains" -> value == null || containsIgnoreCase(milestone, value) || containsIgnoreCase(phase, value);
      case "isEmpty" -> !StringUtils.hasText(milestone) && !StringUtils.hasText(phase);
      case "isNotEmpty" -> StringUtils.hasText(milestone) || StringUtils.hasText(phase);
      default -> true;
    };
  }

  private static boolean matchesResolvedPhase(
      String testingPhase,
      String selectedParent,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (selectedParent == null) {
      return true;
    }
    if (!StringUtils.hasText(testingPhase)) {
      return false;
    }
    return phaseScopeResolver != null
        && phaseScopeResolver.matchesLegacyCrownCadPhase(testingPhase, selectedParent);
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    return left != null && right != null && left.equalsIgnoreCase(right);
  }

  private static boolean containsIgnoreCase(String left, String right) {
    return left != null
        && right != null
        && left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
  }
}
