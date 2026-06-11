package com.data.collection.platform.entity.labelgroup;

public record LabelGroupDynamicRulePreviewRequest(
    String ruleTemplateKey,
    String ruleParamsJson) {}
