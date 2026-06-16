package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupRuleConditionRequest(
    String sourceKey,
    String fieldKey,
    String aggregateKey,
    String operator,
    String value,
    String secondValue,
    List<String> values) {}
