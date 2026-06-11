package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupCreateRequest(
    String name,
    String groupType,
    String description,
    List<LabelGroupMemberRequest> members,
    List<Long> childGroupIds) {}
