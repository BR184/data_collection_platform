package com.data.collection.platform.entity.labelgroup;

import java.time.OffsetDateTime;

public record LabelGroupDynamicRuleResponse(
    String ruleTemplateKey,
    String ruleParamsJson,
    String outputValueType,
    String lastStatus,
    String lastError,
    OffsetDateTime lastComputedAt) {}
