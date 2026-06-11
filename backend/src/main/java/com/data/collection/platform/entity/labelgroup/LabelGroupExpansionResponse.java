package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupExpansionResponse(
    Long groupId,
    String groupName,
    String valueType,
    List<String> values,
    List<LabelGroupMemberResponse> members) {}
