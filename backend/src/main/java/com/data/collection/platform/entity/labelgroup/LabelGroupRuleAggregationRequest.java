package com.data.collection.platform.entity.labelgroup;

public record LabelGroupRuleAggregationRequest(
    String key,
    String sourceKey,
    String fieldKey,
    String function,
    String label) {}
