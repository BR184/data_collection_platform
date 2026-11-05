package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.form.FormHeadCaptureService;
import com.data.collection.platform.service.form.FormInputHash;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 表单消费输入冻结：在读取业务值前锁定/记录全部 FORM 侧证据，并生成规范输入签名。
 *
 * <p>未消费 head 必须领取成功；他人持有时整份候选让位，不能无证公开。已消费侧写入只读证据，
 * 最终公开事务再复核其仍为同一已消费修订。签名与事实/写回作业共享，发送前做当前/事实/作业
 * 三方比对。
 */
@Service
class FormIssueBuildInputService {
  static final String RESOURCE_TYPE = "issue";

  private final FormIssueInputResolver resolver;
  private final FormHeadCaptureService captureService;

  FormIssueBuildInputService(
      FormIssueInputResolver resolver, FormHeadCaptureService captureService) {
    this.resolver = resolver;
    this.captureService = captureService;
  }

  /** 输入冻结结果：议题签名与本次候选绑定的 FORM 证据数量。 */
  record BuildInputs(Map<FormIssueInputResolver.IssueRef, String> hashes, int evidenceCount) {}

  /**
   * 当前输入快照；PENDING 表示已覆盖范围但缺权威/head 证据，NONE 表示无表单权威且不在范围。
   *
   * <p>签名与业务快照来自同一次读取，供标签写回在发送前后复算目标；非 OK 时回复快照为空。
   */
  record Snapshot(Status status, String hash, IssueBusinessInput.ReplySnapshot reply) {
    enum Status {
      OK,
      PENDING,
      NONE
    }
  }


  /**
   * 计算议题当前输入签名与业务快照。
   *
   * <p>先完整读取已提交证据 H1（范围集合→权威→head），再读取回复业务值，最后重新完整读取证据 H2；
   * H1 与 H2 不一致说明读取期间来源、权威、集合或 head 已推进，返回 {@code PENDING} 等待原事实链收敛，
   * 绝不返回旧签名搭配新原因的组合。只读权威/集合/head 修订，不读取业务值以外的写状态。
   *
   * @param sourceInstance 来源实例
   * @param projectId 项目
   * @param issueIid 议题 IID
   * @return 当前快照；缺失、冲突或读取期间推进返回 PENDING
   */
  Snapshot current(String sourceInstance, long projectId, long issueIid) {
    FormIssueInputResolver.IssueRef ref =
        new FormIssueInputResolver.IssueRef(projectId, issueIid);
    try {
      EvidenceRead before = readEvidence(sourceInstance, ref);
      if (!before.adopted()) {
        return new Snapshot(
            before.coveringScope() ? Snapshot.Status.PENDING : Snapshot.Status.NONE, null, null);
      }
      if (before.hash() == null) {
        return new Snapshot(Snapshot.Status.PENDING, null, null);
      }
      IssueBusinessInput input =
          resolver.resolveAll(sourceInstance, List.of(ref)).inputs().get(ref);
      EvidenceRead after = readEvidence(sourceInstance, ref);
      if (!after.adopted() || after.hash() == null || !before.hash().equals(after.hash())) {
        return new Snapshot(Snapshot.Status.PENDING, null, null);
      }
      IssueBusinessInput.ReplySnapshot reply =
          input == null ? IssueBusinessInput.ReplySnapshot.absent() : input.reply();
      return new Snapshot(Snapshot.Status.OK, before.hash(), reply);
    } catch (FormConflictException error) {
      return new Snapshot(Snapshot.Status.PENDING, null, null);
    }
  }

  /**
   * 完整读取一次已提交证据：范围集合→权威→head，并计算输入签名。
   *
   * <p>每次调用都重新加载全部证据，不能复用上一次读取的范围或权威；权威为空时签名缺失，
   * 由调用方区分“无覆盖范围”和“读取期间权威消失”。
   */
  private EvidenceRead readEvidence(String sourceInstance, FormIssueInputResolver.IssueRef ref) {
    List<FormIssueInputResolver.ScopeRow> scopes = resolver.loadScopes(sourceInstance);
    Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>>
        authorities = resolver.loadAuthorities(sourceInstance, List.of(ref), scopes);
    Map<String, FormIssueInputResolver.AuthorityRow> byTemplate =
        authorities.getOrDefault(ref, Map.of());
    if (byTemplate.isEmpty()) {
      return new EvidenceRead(false, null, resolver.hasCoveringScope(scopes, ref));
    }
    Map<Long, FormIssueInputResolver.ScopeRow> scopesById = new HashMap<>();
    for (FormIssueInputResolver.ScopeRow scope : scopes) {
      scopesById.put(scope.scopeId(), scope);
    }
    return new EvidenceRead(
        true, evidenceHash(sourceInstance, ref, byTemplate, scopesById), true);
  }

  private record EvidenceRead(boolean adopted, String hash, boolean coveringScope) {}

  /**
   * 读取一次已提交证据并计算输入签名。
   *
   * <p>范围集合、来源、权威修订与 head 证据均进入签名；范围/集合/head 缺失时返回 {@code null}，
   * 由调用方按 PENDING 处理。
   */
  private String evidenceHash(
      String sourceInstance,
      FormIssueInputResolver.IssueRef ref,
      Map<String, FormIssueInputResolver.AuthorityRow> byTemplate,
      Map<Long, FormIssueInputResolver.ScopeRow> scopesById) {
    return evidenceHash(ref, byTemplate, scopesById, loadFormHeads(sourceInstance, Map.of(ref, byTemplate)));
  }

  /** 由同一批次已冻结的范围、权威和head生成签名，不按每个事实重复查询。 */
  Map<FormIssueInputResolver.IssueRef, String> frozenHashes(String sourceInstance,
      Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>> authorities,
      List<FormIssueInputResolver.ScopeRow> scopes) {
    Map<Long, FormIssueInputResolver.ScopeRow> scopesById = new HashMap<>();
    for (FormIssueInputResolver.ScopeRow scope : scopes) { scopesById.put(scope.scopeId(), scope); }
    Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> heads = loadFormHeads(sourceInstance, authorities);
    Map<FormIssueInputResolver.IssueRef, String> result = new HashMap<>();
    for (var entry : authorities.entrySet()) {
      String hash = evidenceHash(entry.getKey(), entry.getValue(), scopesById, heads);
      if (hash == null) { throw new FormConflictException("表单输入缺少完整head证据"); }
      result.put(entry.getKey(), hash);
    }
    return result;
  }

  private String evidenceHash(FormIssueInputResolver.IssueRef ref,
      Map<String, FormIssueInputResolver.AuthorityRow> byTemplate,
      Map<Long, FormIssueInputResolver.ScopeRow> scopesById,
      Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> heads) {
    List<FormInputHash.Side> sides = new ArrayList<>();
    for (FormIssueInputResolver.AuthorityRow authority :
        byTemplate.values().stream()
            .sorted(Comparator.comparing(FormIssueInputResolver.AuthorityRow::templateKey))
            .toList()) {
      FormIssueInputResolver.ScopeRow scope = scopesById.get(authority.scopeId());
      if (!"FORM".equals(authority.authority())) {
        sides.add(
            new FormInputHash.Side(
                authority.templateKey(),
                "COMMENT",
                authority.authorityRevision(),
                authority.scopeId(),
                scope == null ? null : scope.sourceSetRevision(),
                scope == null ? null : scope.sourceSetHash(),
                null,
                null));
        continue;
      }
      if (scope == null
          || scope.sourceSetRevision() == null
          || scope.sourceSetHash() == null) {
        return null;
      }
      FormHeadCaptureService.HeadRow head =
          heads.get(
              new FormHeadCaptureService.HeadKey(
                  ref.projectId(), ref.issueIid(), authority.templateKey()));
      if (head == null) {
        return null;
      }
      sides.add(
          new FormInputHash.Side(
              authority.templateKey(),
              "FORM",
              authority.authorityRevision(),
              authority.scopeId(),
              scope.sourceSetRevision(),
              scope.sourceSetHash(),
              head.id(),
              head.latestChangeVersion()));
    }
    FormIssueInputResolver.AuthorityRow first = byTemplate.values().iterator().next();
    return FormInputHash.issueHash(
        first.sourceConfigId(),
        first.sourceInstance(),
        first.canonicalUrl(),
        ref.projectId(),
        first.resourceType(),
        Long.toString(ref.issueIid()),
        sides);
  }


  private Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> loadFormHeads(
      String sourceInstance,
      Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>>
          authorities) {
    List<FormHeadCaptureService.HeadKey> keys = new ArrayList<>();
    for (Map.Entry<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>>
        entry : authorities.entrySet()) {
      for (FormIssueInputResolver.AuthorityRow authority : entry.getValue().values()) {
        if ("FORM".equals(authority.authority())) {
          keys.add(
              new FormHeadCaptureService.HeadKey(
                  entry.getKey().projectId(),
                  entry.getKey().issueIid(),
                  authority.templateKey()));
        }
      }
    }
    if (keys.isEmpty()) {
      return Map.of();
    }
    return captureService.loadHeads(sourceInstance, RESOURCE_TYPE, keys);
  }
}
