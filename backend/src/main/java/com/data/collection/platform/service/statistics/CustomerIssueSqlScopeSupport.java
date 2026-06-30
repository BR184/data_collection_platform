package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class CustomerIssueSqlScopeSupport {
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);

  private CustomerIssueSqlScopeSupport() {}

  static SqlScope boardScope(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    LinkedHashMap<String, String> queryFilters =
        new LinkedHashMap<>(filters == null ? Map.of() : filters);
    queryFilters.remove(CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD);
    queryFilters.remove(CustomerIssueMilestoneFilterSupport.LEGACY_TESTING_PHASE_FIELD);
    queryFilters.put("projectId", String.valueOf(LEGACY_CC_PRODUCT_PROJECT_ID));

    List<Object> args = new ArrayList<>();
    StringBuilder predicate = new StringBuilder();
    predicate.append("(created_at_source is null or created_at_source >= ?)");
    args.add(CUSTOMER_ISSUE_START_DATE.atStartOfDay());
    predicate.append(" and coalesce(is_excluded, false) = false");

    String milestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(filterGroup);
    if (StringUtils.hasText(milestone)) {
      predicate.append(" and lower(coalesce(milestone_title, '')) = ?");
      args.add(milestone.toLowerCase(Locale.ROOT));
    } else {
      predicate.append(" and coalesce(milestone_title, '') <> ''");
    }
    return new SqlScope(queryFilters, predicate.toString(), args);
  }

  static List<StatisticFilterGroup> milestoneFilterGroups(
      List<StatisticFilterOption> options,
      int limit) {
    List<StatisticFilterGroup> groups =
        (options == null ? List.<StatisticFilterOption>of() : options).stream()
            .map(StatisticFilterOption::value)
            .filter(StringUtils::hasText)
            .distinct()
            .limit(Math.max(0, limit))
            .map(CustomerIssueSqlScopeSupport::milestoneFilterGroup)
            .toList();
    return groups.isEmpty() ? List.of(new StatisticFilterGroup("AND", List.of())) : groups;
  }

  private static StatisticFilterGroup milestoneFilterGroup(String milestone) {
    return new StatisticFilterGroup(
        "AND",
        List.of(new StatisticFilterCondition(
            CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD,
            "eq",
            milestone,
            null)));
  }

  record SqlScope(Map<String, String> filters, String predicate, List<Object> args) {}
}
