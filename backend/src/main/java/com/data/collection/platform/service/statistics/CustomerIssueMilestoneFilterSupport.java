package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.CustomerIssuePhaseSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

final class CustomerIssueMilestoneFilterSupport {
  static final String MILESTONE_FIELD = "milestoneTitle";
  static final String LEGACY_TESTING_PHASE_FIELD = CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD;

  private CustomerIssueMilestoneFilterSupport() {}

  static StatisticFilterGroup applyDefaultMilestone(
      StatisticFilterGroup filterGroup,
      List<String> milestoneOptions,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    StatisticFilterGroup normalized = normalizeLegacyTestingPhase(filterGroup, phaseScopeResolver);
    if (StringUtils.hasText(selectedMilestone(normalized))) {
      return normalized;
    }
    String defaultMilestone =
        CustomerIssueMilestoneOrdering.latest(safeMilestones(milestoneOptions));
    if (!StringUtils.hasText(defaultMilestone)) {
      return normalized == null ? new StatisticFilterGroup("AND", List.of()) : normalized;
    }
    List<StatisticFilterCondition> conditions = new ArrayList<>();
    if (normalized != null && normalized.conditions() != null) {
      conditions.addAll(normalized.conditions());
    }
    conditions.add(new StatisticFilterCondition(MILESTONE_FIELD, "eq", defaultMilestone, null));
    return new StatisticFilterGroup("AND", conditions);
  }

  static StatisticFilterGroup normalizeLegacyTestingPhase(
      StatisticFilterGroup filterGroup,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return filterGroup;
    }
    boolean changed = false;
    List<StatisticFilterCondition> normalized = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      if (condition != null && LEGACY_TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
        changed = true;
        normalized.add(new StatisticFilterCondition(
            MILESTONE_FIELD,
            condition.operator(),
            condition.value(),
            condition.secondaryValue(),
            condition.valueType(),
            condition.labelGroupId(),
            condition.labelGroupName(),
            condition.values()));
      } else {
        normalized.add(condition);
      }
    }
    return changed ? new StatisticFilterGroup(filterGroup.logic(), normalized) : filterGroup;
  }

  static boolean matches(String milestoneTitle, String testingPhase, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    List<StatisticFilterCondition> milestoneConditions =
        filterGroup.conditions().stream()
            .filter(condition -> condition != null && MILESTONE_FIELD.equals(condition.fieldKey()))
            .toList();
    if (milestoneConditions.isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : milestoneConditions) {
      boolean matched = matchesCondition(milestoneTitle, testingPhase, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  static String selectedMilestone(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return "";
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && MILESTONE_FIELD.equals(condition.fieldKey()))
        .filter(condition -> "eq".equals(condition.operator()))
        .map(StatisticFilterCondition::value)
        .map(TextQuerySupport::trimToNull)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("");
  }

  static MapWithPayload snapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      long projectId) {
    LinkedHashMap<String, String> payload = new LinkedHashMap<>(filters == null ? java.util.Map.of() : filters);
    payload.put("projectId", String.valueOf(projectId));
    String selectedMilestone = selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(selectedMilestone)) {
      payload.put(MILESTONE_FIELD, selectedMilestone);
      payload.remove(LEGACY_TESTING_PHASE_FIELD);
    }
    return new MapWithPayload(payload, selectedMilestone);
  }

  private static boolean matchesCondition(
      String milestoneTitle,
      String testingPhase,
      StatisticFilterCondition condition) {
    String value = TextQuerySupport.trimToNull(condition.value());
    String milestone = TextQuerySupport.trimToNull(milestoneTitle);
    String phase = TextQuerySupport.trimToNull(testingPhase);
    return switch (condition.operator()) {
      case "eq" -> value == null || CustomerIssuePhaseSupport.matchesSelectedPhase(milestone, phase, value, null);
      case "ne" -> value == null && phase == null && milestone == null
          || !CustomerIssuePhaseSupport.matchesSelectedPhase(milestone, phase, value, null);
      case "contains" -> value == null || containsIgnoreCase(milestone, value) || containsIgnoreCase(phase, value);
      case "isEmpty" -> !StringUtils.hasText(milestone) && !StringUtils.hasText(phase);
      case "isNotEmpty" -> StringUtils.hasText(milestone) || StringUtils.hasText(phase);
      default -> true;
    };
  }

  private static boolean containsIgnoreCase(String left, String right) {
    return left != null
        && right != null
        && left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
  }

  private static List<String> safeMilestones(List<String> milestoneOptions) {
    return milestoneOptions == null ? List.of() : milestoneOptions;
  }

  record MapWithPayload(LinkedHashMap<String, String> filters, String selectedMilestone) {}
}
