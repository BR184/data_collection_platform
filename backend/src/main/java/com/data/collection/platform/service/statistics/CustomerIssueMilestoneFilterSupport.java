package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

final class CustomerIssueMilestoneFilterSupport {
  static final String MILESTONE_FIELD = "milestoneTitle";
  private CustomerIssueMilestoneFilterSupport() {}

  static StatisticFilterGroup applyDefaultMilestone(
      StatisticFilterGroup filterGroup,
      CustomerIssueMilestoneCatalogService milestoneCatalogService) {
    if (StringUtils.hasText(selectedMilestone(filterGroup))) {
      return filterGroup;
    }
    String defaultMilestone = milestoneCatalogService.defaultMilestone();
    if (!StringUtils.hasText(defaultMilestone)) {
      return filterGroup == null ? new StatisticFilterGroup("AND", List.of()) : filterGroup;
    }
    List<StatisticFilterCondition> conditions = new ArrayList<>();
    if (filterGroup != null && filterGroup.conditions() != null) {
      conditions.addAll(filterGroup.conditions());
    }
    conditions.add(new StatisticFilterCondition(MILESTONE_FIELD, "eq", defaultMilestone, null));
    return new StatisticFilterGroup("AND", conditions);
  }

  static boolean matches(
      String milestoneTitle,
      StatisticFilterGroup filterGroup,
      CustomerIssueMilestoneCatalogService milestoneCatalogService) {
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
      boolean matched = matchesCondition(milestoneTitle, condition, milestoneCatalogService);
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
    }
    return new MapWithPayload(payload, selectedMilestone);
  }

  private static boolean matchesCondition(
      String milestoneTitle,
      StatisticFilterCondition condition,
      CustomerIssueMilestoneCatalogService milestoneCatalogService) {
    String value = TextQuerySupport.trimToNull(condition.value());
    String milestone = TextQuerySupport.trimToNull(milestoneTitle);
    return switch (condition.operator()) {
      case "eq" -> value == null || milestoneCatalogService.matches(value, milestone);
      case "ne" -> value == null && milestone == null
          || !milestoneCatalogService.matches(value, milestone);
      case "contains" -> value == null || containsIgnoreCase(milestone, value);
      case "isEmpty" -> !StringUtils.hasText(milestone);
      case "isNotEmpty" -> StringUtils.hasText(milestone);
      default -> true;
    };
  }

  private static boolean containsIgnoreCase(String left, String right) {
    return left != null
        && right != null
        && left.toLowerCase(java.util.Locale.ROOT)
            .contains(right.toLowerCase(java.util.Locale.ROOT));
  }

  record MapWithPayload(LinkedHashMap<String, String> filters, String selectedMilestone) {}
}
