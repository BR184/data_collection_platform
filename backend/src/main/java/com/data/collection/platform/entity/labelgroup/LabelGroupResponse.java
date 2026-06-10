package com.data.collection.platform.entity.labelgroup;

import java.time.OffsetDateTime;
import java.util.List;

public record LabelGroupResponse(
    Long id,
    String name,
    String dimensionKey,
    String dimensionName,
    String groupType,
    String description,
    boolean enabled,
    int memberCount,
    List<LabelGroupMemberResponse> members,
    String createdBy,
    OffsetDateTime createdAt,
    String updatedBy,
    OffsetDateTime updatedAt) {}
