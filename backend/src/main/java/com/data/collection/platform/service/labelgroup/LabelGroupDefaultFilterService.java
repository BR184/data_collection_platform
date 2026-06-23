package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDefaultFilterService {
  private static final String MODULE_FIELD = "moduleName";
  private static final String SYSTEM_TEST_DEFECT_SUMMARY_PAGE_KEY = "system-test-defect-summary";
  private static final String STRING_VALUE_TYPE = "STRING";
  private static final Map<String, DefaultLabelGroupRule> DEFAULT_RULES =
      Map.of(
          ruleKey(SYSTEM_TEST_DEFECT_SUMMARY_PAGE_KEY, MODULE_FIELD),
          new DefaultLabelGroupRule(
              SYSTEM_TEST_DEFECT_SUMMARY_PAGE_KEY,
              MODULE_FIELD,
              STRING_VALUE_TYPE,
              "intersects",
              LabelGroupService.SYSTEM_TEST_DEFECT_SUMMARY_DEFAULT_GROUP_NAME));

  private final LabelGroupService labelGroupService;

  public LabelGroupDefaultFilterService(LabelGroupService labelGroupService) {
    this.labelGroupService = labelGroupService;
  }

  public Optional<StatisticFilterCondition> defaultCondition(String pageKey, String fieldKey) {
    DefaultLabelGroupRule rule = DEFAULT_RULES.get(ruleKey(pageKey, fieldKey));
    if (rule == null) {
      return Optional.empty();
    }
    return labelGroupService
        .expandSystemDefault(rule.groupName(), rule.valueType())
        .map(expanded -> new StatisticFilterCondition(
            rule.fieldKey(),
            rule.operator(),
            null,
            null,
            "LABEL_GROUP",
            expanded.groupId(),
            expanded.groupName(),
            expanded.values()));
  }

  private static String ruleKey(String pageKey, String fieldKey) {
    return String.valueOf(pageKey) + ":" + String.valueOf(fieldKey);
  }

  private record DefaultLabelGroupRule(
      String pageKey,
      String fieldKey,
      String valueType,
      String operator,
      String groupName) {}
}
