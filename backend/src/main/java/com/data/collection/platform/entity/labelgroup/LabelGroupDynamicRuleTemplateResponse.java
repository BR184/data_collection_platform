package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupDynamicRuleTemplateResponse(
    String key,
    String name,
    String description,
    String outputValueType,
    String outputDescription,
    List<LabelGroupDynamicRuleTemplateParameterResponse> parameters) {}
