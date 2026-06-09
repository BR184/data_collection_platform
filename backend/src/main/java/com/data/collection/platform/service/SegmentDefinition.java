package com.data.collection.platform.service;

import java.util.List;

public record SegmentDefinition(
    long id,
    String name,
    SegmentType segmentType,
    String entityType,
    String scenarioKey,
    SemanticScopePlan scopePlan,
    String ruleJson,
    SegmentMemberSource memberSource,
    String tagSchemaHash,
    SegmentDefinitionStatus status,
    String createdBy,
    SegmentExecutionPlan executionPlan,
    List<SegmentMember> previewMembers,
    List<SegmentMemberAuditEntry> auditEntries) {
  public SegmentDefinition {
    previewMembers = List.copyOf(previewMembers);
    auditEntries = List.copyOf(auditEntries);
  }

  SegmentDefinition withStatus(SegmentDefinitionStatus status, SegmentMemberAuditEntry auditEntry) {
    List<SegmentMemberAuditEntry> entries =
        java.util.stream.Stream.concat(auditEntries.stream(), java.util.stream.Stream.of(auditEntry))
            .toList();
    return new SegmentDefinition(
        id,
        name,
        segmentType,
        entityType,
        scenarioKey,
        scopePlan,
        ruleJson,
        memberSource,
        tagSchemaHash,
        status,
        createdBy,
        executionPlan,
        previewMembers,
        entries);
  }
}
