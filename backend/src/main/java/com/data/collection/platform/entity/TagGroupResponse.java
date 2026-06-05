package com.data.collection.platform.entity;

import java.util.List;

public record TagGroupResponse(
    String groupKey,
    String label,
    String selectionMode,
    int sortOrder,
    String matchStrategyName,
    List<TagGroupValueResponse> values) {
}
