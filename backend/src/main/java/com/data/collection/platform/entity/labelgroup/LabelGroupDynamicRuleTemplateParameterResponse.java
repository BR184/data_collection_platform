package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupDynamicRuleTemplateParameterResponse(
    String key,
    String label,
    String controlType,
    boolean required,
    Object defaultValue,
    List<LabelGroupDynamicRuleTemplateOptionResponse> options) {}
