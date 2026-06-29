package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

final class SystemTestPhaseSqlPredicateSupport {
  private static final String TESTING_PHASE_FIELD = "testingPhase";
  private static final SqlPredicate EMPTY = new SqlPredicate("", List.of());

  private SystemTestPhaseSqlPredicateSupport() {}

  static SqlPredicate legacyStatisticPhasePredicate(
      StatisticFilterGroup filterGroup, SystemTestPhaseScopeResolver phaseScopeResolver) {
    String selectedPhase = selectedExactTestingPhase(filterGroup);
    if (!StringUtils.hasText(selectedPhase) || phaseScopeResolver == null) {
      return EMPTY;
    }
    List<String> phases =
        phaseScopeResolver.resolveLegacyCrownCadPhases(selectedPhase).stream()
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    if (phases.isEmpty()) {
      return EMPTY;
    }

    List<String> clauses = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String phase : phases) {
      clauses.add("testing_phase like ?");
      args.add("%" + phase + "%");
    }
    return new SqlPredicate(String.join(" or ", clauses), args);
  }

  private static String selectedExactTestingPhase(StatisticFilterGroup filterGroup) {
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
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  record SqlPredicate(String sql, List<Object> args) {}
}
