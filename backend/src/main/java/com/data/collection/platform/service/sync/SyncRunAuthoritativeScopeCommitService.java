package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.VersionedFactChangeTarget;
import com.data.collection.platform.service.FactChangeTargetService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在一个平台事务内替换权威范围、登记目标并完成范围状态。 */
@Service
public class SyncRunAuthoritativeScopeCommitService {
  private final SyncRunAuthoritativeScopeRepository repository;
  private final MirrorTableWriter mirrorTableWriter;
  private final FactChangeTargetService factChangeTargetService;
  private final SyncRunReconciliationCoordinator reconciliationCoordinator;

  public SyncRunAuthoritativeScopeCommitService(
      SyncRunAuthoritativeScopeRepository repository,
      MirrorTableWriter mirrorTableWriter,
      FactChangeTargetService factChangeTargetService,
      SyncRunReconciliationCoordinator reconciliationCoordinator) {
    this.repository = repository;
    this.mirrorTableWriter = mirrorTableWriter;
    this.factChangeTargetService = factChangeTargetService;
    this.reconciliationCoordinator = reconciliationCoordinator;
  }

  /**
   * 提交一个来源批次的完整范围结果。
   *
   * <p>所有请求范围都必须出现在 {@code sourceRowsByScope}; 空列表代表权威清空。
   */
  @Transactional
  public CommitResult commit(
      List<SyncRunAuthoritativeScope> scopes,
      String owner,
      SourceTableSchema mirrorSchema,
      Map<Long, List<Map<String, Object>>> sourceRowsByScope) {
    ScopeBatchIdentity identity = validateBatch(scopes, owner, sourceRowsByScope);
    List<Long> scopeIds = scopes.stream().map(SyncRunAuthoritativeScope::id).toList();
    repository.lockOwnedBatch(scopeIds, owner, identity.runId());
    reconciliationCoordinator.lockStageMutation(identity.runId());
    ArrayList<MirrorRowChange> changes = new ArrayList<>();
    long sourceRows = 0L;
    long appliedRows = 0L;
    for (SyncRunAuthoritativeScope scope : scopes) {
      List<Map<String, Object>> rows = sourceRowsByScope.get(scope.id());
      MirrorMutationResult result =
          mirrorTableWriter.replaceAuthoritativeScope(
              mirrorSchema, scope.lookupScope(), rows, scope.taskId());
      sourceRows += rows.size();
      appliedRows += result.appliedRows();
      changes.addAll(result.changes());
    }
    List<VersionedFactChangeTarget> targets =
        changes.isEmpty()
            ? List.of()
            : factChangeTargetService.registerChanges(
                identity.runId(),
                diagnosticTaskId(scopes),
                identity.sourceInstance(),
                identity.childTable(),
                changes);
    repository.completeOwnedBatch(scopeIds, owner, identity.runId());
    reconciliationCoordinator.planIfReady(identity.runId());
    return new CommitResult(scopes.size(), sourceRows, appliedRows, targets.size());
  }

  private ScopeBatchIdentity validateBatch(
      List<SyncRunAuthoritativeScope> scopes,
      String owner,
      Map<Long, List<Map<String, Object>>> sourceRowsByScope) {
    if (scopes == null || scopes.isEmpty() || owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("权威范围提交批次不能为空");
    }
    SyncRunAuthoritativeScope first = scopes.getFirst();
    boolean inconsistent =
        scopes.stream()
            .anyMatch(
                scope ->
                    scope.runId() != first.runId()
                        || !first.sourceInstance().equals(scope.sourceInstance())
                        || !first.childTable().equals(scope.childTable())
                        || !first.relationKey().equals(scope.relationKey())
                        || !owner.equals(scope.leaseOwner()));
    if (inconsistent) {
      throw new IllegalArgumentException("权威范围提交批次身份不一致");
    }
    if (sourceRowsByScope == null
        || !sourceRowsByScope.keySet().equals(
            scopes.stream()
                .map(SyncRunAuthoritativeScope::id)
                .collect(java.util.stream.Collectors.toSet()))) {
      throw new IllegalArgumentException("来源批量结果没有完整覆盖权威范围");
    }
    return new ScopeBatchIdentity(
        first.runId(), first.sourceInstance(), first.childTable(), first.relationKey());
  }

  private Long diagnosticTaskId(List<SyncRunAuthoritativeScope> scopes) {
    return scopes.stream()
        .map(SyncRunAuthoritativeScope::taskId)
        .filter(java.util.Objects::nonNull)
        .max(Long::compareTo)
        .orElse(null);
  }

  private record ScopeBatchIdentity(
      long runId, String sourceInstance, String childTable, String relationKey) {}

  /** 权威范围批次提交计数。 */
  public record CommitResult(
      int completedScopes, long sourceRows, long appliedRows, int factTargets) {}
}
