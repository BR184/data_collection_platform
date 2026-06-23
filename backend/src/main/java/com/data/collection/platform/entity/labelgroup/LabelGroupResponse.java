package com.data.collection.platform.entity.labelgroup;

import java.time.OffsetDateTime;
import java.util.List;

public record LabelGroupResponse(
    Long id,
    String name,
    String valueType,
    String groupType,
    String description,
    boolean enabled,
    int memberCount,
    List<LabelGroupMemberResponse> members,
    List<LabelGroupChildResponse> childGroups,
    LabelGroupDynamicRuleResponse dynamicRule,
    List<LabelGroupMemberResponse> expandedPreview,
    boolean systemDefault,
    String createdBy,
    OffsetDateTime createdAt,
    String updatedBy,
    OffsetDateTime updatedAt) {}
