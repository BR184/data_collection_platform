package com.data.collection.platform.entity.labelgroup;

public record LabelGroupDynamicRuleRequest(
    String ruleTemplateKey,
    String ruleParamsJson) {}
