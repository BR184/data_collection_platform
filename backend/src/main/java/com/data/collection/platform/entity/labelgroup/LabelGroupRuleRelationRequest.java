package com.data.collection.platform.entity.labelgroup;

public record LabelGroupRuleRelationRequest(
    String leftSourceKey,
    String leftFieldKey,
    String rightSourceKey,
    String rightFieldKey,
    String matchOperator,
    String normalizer) {}
