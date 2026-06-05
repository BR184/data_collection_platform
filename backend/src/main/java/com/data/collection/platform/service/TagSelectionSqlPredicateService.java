package com.data.collection.platform.service;

import com.data.collection.platform.entity.TagGroupResponse;
import com.data.collection.platform.entity.TagGroupValueResponse;
import com.data.collection.platform.entity.TagSelectionRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TagSelectionSqlPredicateService {
  private static final Map<String, String> ISSUE_EQ_COLUMNS =
      Map.of(
          "severity", "severity_level",
          "severity_level", "severity_level",
          "milestone", "milestone_title",
          "testing_phase", "phase_filter_value",
          "status", "issue_state");

  private final TagGroupService tagGroupService;

  public TagSelectionSqlPredicateService(TagGroupService tagGroupService) {
    this.tagGroupService = tagGroupService;
  }

  Optional<SqlPredicate> toSql(
      String domain, String sourceInstance, List<TagSelectionRequest> selections) {
    if (selections == null || selections.isEmpty()) {
      return Optional.of(new SqlPredicate("", List.of()));
    }
    String normalizedDomain = normalizeDomain(domain);
    Map<String, TagGroupResponse> groupsByKey = groupsByKey(normalizedDomain);
    List<String> groupPredicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (TagSelectionRequest selection : selections) {
      Optional<SqlPredicate> groupPredicate =
          selectionToSql(normalizedDomain, sourceInstance, groupsByKey, selection);
      if (groupPredicate.isEmpty()) {
        groupPredicates.add("1 = 0");
        continue;
      }
      if (TextQuerySupport.trimToNull(groupPredicate.get().predicate()) != null) {
        groupPredicates.add("(" + groupPredicate.get().predicate() + ")");
        args.addAll(groupPredicate.get().args());
      }
    }
    if (groupPredicates.isEmpty()) {
      return Optional.of(new SqlPredicate("", List.of()));
    }
    return Optional.of(new SqlPredicate(String.join(" and ", groupPredicates), args));
  }

  private Optional<SqlPredicate> selectionToSql(
      String domain,
      String sourceInstance,
      Map<String, TagGroupResponse> groupsByKey,
      TagSelectionRequest selection) {
    if (selection == null || selection.valueKeys() == null || selection.valueKeys().isEmpty()) {
      return Optional.empty();
    }
    String groupKey = normalizeKey(selection.groupKey());
    TagGroupResponse group = groupsByKey.get(groupKey);
    if (group == null) {
      return Optional.empty();
    }
    Map<String, TagGroupValueResponse> valuesByKey = valuesByKey(group);
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String valueKey : selection.valueKeys()) {
      TagGroupValueResponse value = valuesByKey.get(normalizeKey(valueKey));
      if (value == null) {
        continue;
      }
      Optional<SqlPredicate> valuePredicate = valueToSql(domain, group, value, sourceInstance);
      valuePredicate.ifPresent(
          predicate -> {
            predicates.add(predicate.predicate());
            args.addAll(predicate.args());
          });
    }
    if (predicates.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private Optional<SqlPredicate> valueToSql(
      String domain,
      TagGroupResponse group,
      TagGroupValueResponse value,
      String sourceInstance) {
    List<String> candidates = candidates(domain, group.groupKey(), value, sourceInstance);
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    String strategy = TagGroupMatchStrategyRegistry.normalize(group.matchStrategyName());
    return switch (strategy) {
      case TagGroupMatchStrategyRegistry.SPLIT_EXACT_COMMA ->
          splitExactCommaCondition("module_names", candidates);
      case TagGroupMatchStrategyRegistry.ARRAY_EXACT ->
          arrayExactCondition("module_name_array", candidates);
      case TagGroupMatchStrategyRegistry.LIKE ->
          likeCondition(likeColumn(domain, group.groupKey()), candidates);
      case TagGroupMatchStrategyRegistry.EQ ->
          eqCondition(eqColumn(domain, group.groupKey()), candidates);
      default -> Optional.empty();
    };
  }

  private List<String> candidates(
      String domain, String groupKey, TagGroupValueResponse value, String sourceInstance) {
    Set<String> candidates = new LinkedHashSet<>();
    addCandidate(candidates, value.label());
    for (String mapping : tagGroupService.resolveMappings(domain, groupKey, value.valueKey(), sourceInstance)) {
      addCandidate(candidates, mapping);
    }
    return List.copyOf(candidates);
  }

  private Optional<SqlPredicate> splitExactCommaCondition(String column, List<String> candidates) {
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    String expression = "lower(',' || replace(coalesce(" + column + ", ''), ', ', ',') || ',')";
    for (String candidate : candidates) {
      predicates.add(expression + " like ?");
      args.add("%," + lower(candidate) + ",%");
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private Optional<SqlPredicate> arrayExactCondition(String column, List<String> candidates) {
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String candidate : candidates) {
      predicates.add("? = any(coalesce(" + column + ", array[]::text[]))");
      args.add(candidate);
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private Optional<SqlPredicate> likeCondition(String column, List<String> candidates) {
    if (column == null) {
      return Optional.empty();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    String expression = "lower(coalesce(" + column + ", ''))";
    for (String candidate : candidates) {
      String normalized = lower(candidate);
      List<String> pieces = new ArrayList<>();
      pieces.add(expression + " like ?");
      args.add("%" + normalized + "%");
      if ("曲线".equals(candidate) || "曲面".equals(candidate)) {
        pieces.add(expression + " not like ?");
        args.add("%曲线曲面%");
      }
      predicates.add("(" + String.join(" and ", pieces) + ")");
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private Optional<SqlPredicate> eqCondition(String column, List<String> candidates) {
    if (column == null) {
      return Optional.empty();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    String expression = "lower(coalesce(" + column + ", ''))";
    for (String candidate : candidates) {
      predicates.add(expression + " = ?");
      args.add(lower(candidate));
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private Map<String, TagGroupResponse> groupsByKey(String domain) {
    Map<String, TagGroupResponse> groupsByKey = new LinkedHashMap<>();
    for (TagGroupResponse group : tagGroupService.getTagGroups(domain).groups()) {
      groupsByKey.put(normalizeKey(group.groupKey()), group);
    }
    return groupsByKey;
  }

  private static Map<String, TagGroupValueResponse> valuesByKey(TagGroupResponse group) {
    Map<String, TagGroupValueResponse> valuesByKey = new LinkedHashMap<>();
    for (TagGroupValueResponse value : group.values()) {
      valuesByKey.put(normalizeKey(value.valueKey()), value);
    }
    return valuesByKey;
  }

  private static String likeColumn(String domain, String groupKey) {
    if ("issue".equals(domain)
        && ("module".equals(normalizeKey(groupKey)) || "module_keyword".equals(normalizeKey(groupKey)))) {
      return "module_names";
    }
    return null;
  }

  private static String eqColumn(String domain, String groupKey) {
    if ("issue".equals(domain)) {
      return ISSUE_EQ_COLUMNS.get(normalizeKey(groupKey));
    }
    if ("review_data".equals(domain) && "module".equals(normalizeKey(groupKey))) {
      return "r.module_name";
    }
    return null;
  }

  private static void addCandidate(Set<String> candidates, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized != null) {
      candidates.add(normalized);
    }
  }

  private static String normalizeDomain(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "issue" : normalized.toLowerCase(Locale.ROOT);
  }

  private static String normalizeKey(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private static String lower(String value) {
    return TextQuerySupport.normalizeDisplay(value).toLowerCase(Locale.ROOT);
  }
}
