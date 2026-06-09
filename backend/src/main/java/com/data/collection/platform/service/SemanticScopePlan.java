package com.data.collection.platform.service;

import java.util.List;

public record SemanticScopePlan(
    String entityType,
    ScopeCompositionMode compositionMode,
    List<String> scopeKeys,
    List<String> sqlTemplateKeys,
    String planHash) {
  public SemanticScopePlan {
    scopeKeys = List.copyOf(scopeKeys);
    sqlTemplateKeys = List.copyOf(sqlTemplateKeys);
  }
}
