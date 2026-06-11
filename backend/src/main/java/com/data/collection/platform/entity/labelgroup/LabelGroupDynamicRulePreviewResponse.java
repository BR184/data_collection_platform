package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupDynamicRulePreviewResponse(
    String ruleTemplateKey,
    String outputValueType,
    String status,
    String message,
    List<LabelGroupMemberResponse> members) {}
