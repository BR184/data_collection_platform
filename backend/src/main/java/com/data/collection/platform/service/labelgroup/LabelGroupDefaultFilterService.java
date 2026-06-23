package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDefaultFilterService {
  private final LabelGroupService labelGroupService;

  public LabelGroupDefaultFilterService(LabelGroupService labelGroupService) {
    this.labelGroupService = labelGroupService;
  }

  public Optional<StatisticFilterCondition> defaultCondition(String pageKey, String fieldKey) {
    SystemDefaultLabelGroupCatalog.Rule rule = SystemDefaultLabelGroupCatalog.findRule(pageKey, fieldKey);
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
}
