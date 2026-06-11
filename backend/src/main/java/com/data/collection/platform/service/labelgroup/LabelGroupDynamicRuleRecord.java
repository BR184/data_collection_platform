package com.data.collection.platform.service.labelgroup;

import java.time.OffsetDateTime;

record LabelGroupDynamicRuleRecord(
    Long id,
    Long groupId,
    String ruleTemplateKey,
    String ruleParamsJson,
    String outputValueType,
    String lastStatus,
    String lastError,
    OffsetDateTime lastComputedAt) {}
