package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupUpdateRequest(
    String name,
    String dimensionKey,
    String description,
    Boolean enabled,
    List<LabelGroupMemberRequest> members) {}
