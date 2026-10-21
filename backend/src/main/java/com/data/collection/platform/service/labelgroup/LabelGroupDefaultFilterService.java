package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDefaultFilterService {
  private final LabelGroupService labelGroupService;

  /** 系统默认筛选也固定到发布时定义，目录编辑不改写旧页面的查询语义。 */
  public LabelGroupDefaultFilterService forRead(com.data.collection.platform.entity.read.ReadSelection selection) {
    return new LabelGroupDefaultFilterService(labelGroupService.forRead(selection));
  }

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
