package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class SegmentComputeService {
  private final SemanticScopeRegistry scopeRegistry;
  private final SegmentCostEstimator costEstimator;
  private final SemanticQueryExecutor queryExecutor;
  private final SegmentSchemaCompatibilityChecker compatibilityChecker;
  private final AtomicLong ids = new AtomicLong(1);
  private final Map<Long, SegmentDefinition> definitions = new LinkedHashMap<>();

  public SegmentComputeService(
      SemanticScopeRegistry scopeRegistry,
      SegmentCostEstimator costEstimator,
      SemanticQueryExecutor queryExecutor,
      SegmentSchemaCompatibilityChecker compatibilityChecker) {
    this.scopeRegistry = scopeRegistry;
    this.costEstimator = costEstimator;
    this.queryExecutor = queryExecutor;
    this.compatibilityChecker = compatibilityChecker;
  }

  public SegmentDefinition createDefinition(CreateSegmentRequest request) {
    SemanticScopePlan scopePlan = scopeRegistry.compose(request.scopeChain(), request.compositionMode());
    scopeRegistry.validateCompatibility(request.entityType(), request.scopeChain());
    SegmentExecutionContext context =
        new SegmentExecutionContext(request.entityType(), request.scenarioKey(), scopePlan);
    SegmentExecutionPlan executionPlan =
        costEstimator.estimate(
            new SegmentRuleDsl(request.ruleJson()),
            queryExecutor.explain(new SegmentRuleDsl(request.ruleJson()), context));
    List<SegmentMember> previewMembers =
        queryExecutor.executeTemplate(executionPlan.templateName(), Map.of("preview", true));
    long id = ids.getAndIncrement();
    SegmentDefinition definition =
        new SegmentDefinition(
            id,
            request.name(),
            request.segmentType(),
            request.entityType(),
            request.scenarioKey(),
            scopePlan,
            request.ruleJson(),
            request.memberSource(),
            request.tagSchemaHash(),
            SegmentDefinitionStatus.ENABLED,
            request.createdBy(),
            executionPlan,
            previewMembers,
            List.of(audit(id, "CREATE", "definition created", request.createdBy())));
    definitions.put(id, definition);
    return definition;
  }

  public SegmentDefinition createStaticSnapshot(CreateSegmentSnapshotRequest request) {
    SemanticScopePlan scopePlan =
        scopeRegistry.compose(List.of(request.scopeKey()), ScopeCompositionMode.INTERSECT);
    long id = ids.getAndIncrement();
    SegmentDefinition definition =
        new SegmentDefinition(
            id,
            request.name(),
            SegmentType.STATIC,
            request.entityType(),
            request.scenarioKey(),
            scopePlan,
            "{}",
            SegmentMemberSource.SNAPSHOT,
            null,
            SegmentDefinitionStatus.ENABLED,
            request.createdBy(),
            new SegmentExecutionPlan("static_snapshot", 0, 0, 30_000, 50_000),
            List.of(),
            List.of(audit(id, "SNAPSHOT_CREATE", request.snapshotReason(), request.createdBy())));
    definitions.put(id, definition);
    return definition;
  }

  public SegmentDefinition get(long id) {
    SegmentDefinition definition = definitions.get(id);
    if (definition == null) {
      throw new IllegalArgumentException("Unknown segment definition: " + id);
    }
    return definition;
  }

  public SegmentDefinition disable(long id, String operator) {
    SegmentDefinition current = get(id);
    SegmentDefinition disabled =
        current.withStatus(
            SegmentDefinitionStatus.DISABLED, audit(id, "DISABLE", "disabled by operator", operator));
    definitions.put(id, disabled);
    return disabled;
  }

  public CompatibilityResult checkSchemaCompatibility(
      long id, String oldSchemaHash, String newSchemaHash) {
    return compatibilityChecker.check(get(id), oldSchemaHash, newSchemaHash);
  }

  private static SegmentMemberAuditEntry audit(
      long segmentId, String action, String reason, String operator) {
    return new SegmentMemberAuditEntry(segmentId, action, reason, operator, LocalDateTime.now());
  }
}
