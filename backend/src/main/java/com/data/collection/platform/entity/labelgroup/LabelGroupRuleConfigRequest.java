package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupRuleConfigRequest(
    String outputSourceKey,
    String outputFieldKey,
    Boolean distinct,
    LabelGroupRuleConditionGroupRequest filterGroup,
    List<LabelGroupRuleConditionRequest> filters,
    List<LabelGroupRuleRelationRequest> relations,
    List<LabelGroupRuleFieldRefRequest> groupBy,
    List<LabelGroupRuleAggregationRequest> aggregations,
    LabelGroupRuleConditionGroupRequest havingGroup,
    List<LabelGroupRuleConditionRequest> having,
    List<LabelGroupRuleSortRequest> sort,
    Integer limit) {}
