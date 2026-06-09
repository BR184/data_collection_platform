package com.data.collection.platform.service;

import java.util.List;

public record CreateSegmentRequest(
    String name,
    SegmentType segmentType,
    String entityType,
    String scenarioKey,
    List<String> scopeChain,
    ScopeCompositionMode compositionMode,
    String ruleJson,
    SegmentMemberSource memberSource,
    String tagSchemaHash,
    String createdBy) {
  public CreateSegmentRequest {
    scopeChain = List.copyOf(scopeChain);
  }
}
