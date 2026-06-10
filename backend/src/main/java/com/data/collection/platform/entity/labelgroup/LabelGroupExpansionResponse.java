package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupExpansionResponse(
    Long groupId,
    String dimensionKey,
    String dimensionName,
    List<String> values,
    List<LabelGroupMemberResponse> members) {}
