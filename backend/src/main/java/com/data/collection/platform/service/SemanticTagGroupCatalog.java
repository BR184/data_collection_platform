package com.data.collection.platform.service;

import java.util.List;

public record SemanticTagGroupCatalog(
    String entityType, String schemaHash, List<SemanticTagGroupDefinition> groups) {
  public SemanticTagGroupCatalog {
    groups = List.copyOf(groups);
  }

  public SemanticTagGroupDefinition requireGroup(String groupKey) {
    return groups.stream()
        .filter(group -> group.groupKey().equals(groupKey))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown semantic group: " + groupKey));
  }
}
