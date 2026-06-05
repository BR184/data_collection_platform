package com.data.collection.platform.entity;

import java.util.List;

public record TagGroupAdminResponse(
    long id,
    String domain,
    String groupKey,
    String label,
    String selectionMode,
    int sortOrder,
    String matchStrategyName,
    boolean enabled,
    String remark,
    List<TagGroupAdminValueResponse> values) {
}
