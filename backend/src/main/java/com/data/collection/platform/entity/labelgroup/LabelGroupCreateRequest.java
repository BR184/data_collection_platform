package com.data.collection.platform.entity.labelgroup;

import java.util.List;

public record LabelGroupCreateRequest(
    String name,
    String dimensionKey,
    String description,
    List<LabelGroupMemberRequest> members) {}
