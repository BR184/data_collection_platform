package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupRuleConfigRequest(
    String outputSourceKey,
    String outputFieldKey,
    Boolean distinct,
    List<LabelGroupRuleConditionRequest> filters,
    List<LabelGroupRuleRelationRequest> relations,
    List<LabelGroupRuleFieldRefRequest> groupBy,
    List<LabelGroupRuleAggregationRequest> aggregations,
    List<LabelGroupRuleConditionRequest> having,
    List<LabelGroupRuleSortRequest> sort,
    Integer limit) {}
