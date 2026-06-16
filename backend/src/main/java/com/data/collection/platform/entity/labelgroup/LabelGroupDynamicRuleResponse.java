package com.data.collection.platform.entity.labelgroup;

import java.time.OffsetDateTime;

public record LabelGroupDynamicRuleResponse(
    LabelGroupRuleConfigRequest ruleConfig,
    String outputValueType,
    String lastStatus,
    String lastError,
    OffsetDateTime lastComputedAt) {}
