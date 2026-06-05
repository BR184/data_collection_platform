package com.data.collection.platform.entity;

import java.util.List;

public record TagSelectionRequest(
    String groupKey,
    List<String> valueKeys) {
}
