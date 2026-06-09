package com.data.collection.platform.service;

import java.util.List;

@FunctionalInterface
public interface SemanticTagGroupRepository {
  List<SemanticTagGroupDefinition> listEnabledGroups(String entityType);

  static SemanticTagGroupRepository empty() {
    return entityType -> List.of();
  }
}
