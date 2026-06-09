package com.data.collection.platform.service;

import java.util.List;

public record SemanticTagGroupDefinition(
    String domain,
    String groupKey,
    String label,
    String sourceMode,
    String rulePolicyKey,
    String selectionMode,
    String matchStrategyName,
    boolean enabled,
    int sortOrder,
    List<SemanticTagValueDefinition> values) {
  public SemanticTagGroupDefinition {
    values = List.copyOf(values);
  }
}
