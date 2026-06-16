package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupDynamicRuleSourceResponse(
    String key,
    String name,
    String description,
    List<LabelGroupDynamicRuleSourceFieldResponse> fields) {}
