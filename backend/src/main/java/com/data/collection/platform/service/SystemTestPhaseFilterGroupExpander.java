package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import org.springframework.util.StringUtils;

public final class SystemTestPhaseFilterGroupExpander {
  private static final String TESTING_PHASE_FIELD = "testingPhase";

  private SystemTestPhaseFilterGroupExpander() {}

  public static StatisticFilterGroup expand(
      StatisticFilterGroup filterGroup, SystemTestPhaseScopeResolver phaseScopeResolver) {
    return expand(
        filterGroup,
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
        phaseScopeResolver);
  }

  public static StatisticFilterGroup expand(
      StatisticFilterGroup filterGroup,
      Long projectId,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return filterGroup;
    }
    return new StatisticFilterGroup(
        filterGroup.logic(),
        filterGroup.conditions().stream()
            .map(condition -> expandCondition(condition, projectId, phaseScopeResolver))
            .toList());
  }

  private static StatisticFilterCondition expandCondition(
      StatisticFilterCondition condition,
      Long projectId,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    if (condition == null
        || condition.usesLabelGroup()
        || !TESTING_PHASE_FIELD.equals(condition.fieldKey())
        || (!"eq".equals(condition.operator()) && !"ne".equals(condition.operator()))) {
      return condition;
    }
    List<String> phases = phaseScopeResolver.resolvePhases(projectId, condition.value());
    return new StatisticFilterCondition(
        condition.fieldKey(),
        condition.operator(),
        condition.value(),
        condition.secondaryValue(),
        "RESOLVED_LITERAL_SET",
        null,
        null,
        phases.stream().filter(StringUtils::hasText).distinct().toList());
  }
}
