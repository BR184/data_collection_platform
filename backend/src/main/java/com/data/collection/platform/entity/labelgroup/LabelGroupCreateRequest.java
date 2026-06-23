package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupCreateRequest(
    String name,
    String groupType,
    String applicableScope,
    String sourceFieldKey,
    String description,
    List<LabelGroupMemberRequest> members,
    List<Long> childGroupIds,
    LabelGroupDynamicRuleRequest dynamicRule) {}
