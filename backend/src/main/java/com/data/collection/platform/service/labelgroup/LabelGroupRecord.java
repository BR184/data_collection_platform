package com.data.collection.platform.service.labelgroup;

import java.time.OffsetDateTime;
import java.util.List;

record LabelGroupRecord(
    Long id,
    String name,
    String valueType,
    String groupType,
    String description,
    boolean enabled,
    String createdBy,
    OffsetDateTime createdAt,
    String updatedBy,
    OffsetDateTime updatedAt,
    List<LabelGroupMemberRecord> members,
    List<LabelGroupChildRecord> childGroups,
    LabelGroupDynamicRuleRecord dynamicRule) {}
