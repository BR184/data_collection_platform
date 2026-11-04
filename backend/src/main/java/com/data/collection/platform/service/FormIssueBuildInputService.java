package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.read.FactRevisionTarget;
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
   * 在读业务值前冻结候选输入。
   *
   * @param target 候选发布身份
   * @param sourceInstance 来源实例
   * @param refs 参与本次构建的议题
   * @param now 冻结时刻
   * @return 每个议题的输入签名与证据计数
   */
  BuildInputs freeze(
      FactRevisionTarget target,
      String sourceInstance,
      Collection<FormIssueInputResolver.IssueRef> refs,
      LocalDateTime now) {
    if (refs == null || refs.isEmpty()) {
      captureService.completeManifest(target.publicationId());
      return new BuildInputs(Map.of(), 0);
    }
    List<FormIssueInputResolver.IssueRef> distinct =
        refs.stream().filter(java.util.Objects::nonNull).distinct().toList();
    List<FormIssueInputResolver.ScopeRow> scopes = resolver.loadScopes(sourceInstance);
    Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>>
        authorities = resolver.loadAuthorities(sourceInstance, distinct, scopes);
    Map<Long, FormIssueInputResolver.ScopeRow> scopesById = new HashMap<>();
    for (FormIssueInputResolver.ScopeRow scope : scopes) {
      scopesById.put(scope.scopeId(), scope);
    }
    Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> heads =
        loadFormHeads(sourceInstance, authorities);
    String worker = workerOf(target);
    List<FormHeadCaptureService.InputEvidence> evidence = new ArrayList<>();
    Map<FormIssueInputResolver.IssueRef, String> hashes = new LinkedHashMap<>();
    for (FormIssueInputResolver.IssueRef ref : distinct) {
      Map<String, FormIssueInputResolver.AuthorityRow> byTemplate =
          authorities.getOrDefault(ref, Map.of());
      List<FormInputHash.Side> sides = new ArrayList<>();
      for (FormIssueInputResolver.AuthorityRow authority :
          byTemplate.values().stream()
              .sorted(Comparator.comparing(FormIssueInputResolver.AuthorityRow::templateKey))
              .toList()) {
        if (!"FORM".equals(authority.authority())) {
          FormIssueInputResolver.ScopeRow scope = scopesById.get(authority.scopeId());
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
        FormHeadCaptureService.HeadKey key =
            new FormHeadCaptureService.HeadKey(
                ref.projectId(), ref.issueIid(), authority.templateKey());
        FormHeadCaptureService.HeadRow head = heads.get(key);
        if (head == null) {
          throw new FormConflictException(
              "议题 " + ref.issueIid() + " 的 " + authority.templateKey()
                  + " 缺少变更 head，拒绝无证构建");
        }
        FormHeadCaptureService.InputEvidence frozen =
            captureService.ensureEvidence(authority.scopeId(), head.id(), worker, now);
        evidence.add(frozen);
        FormHeadCaptureService.Capture capture = frozen.capture();
        sides.add(
            new FormInputHash.Side(
                authority.templateKey(),
                "FORM",
                capture.authorityRevision(),
                authority.scopeId(),
                capture.sourceSetRevision(),
                capture.sourceSetHash(),
                capture.headId(),
                capture.latestChangeVersion()));
      }
      if (sides.isEmpty()) {
        if (resolver.hasCoveringScope(scopes, ref)) {
          hashes.put(ref, null);
        }
        continue;
      }
      FormIssueInputResolver.AuthorityRow first = byTemplate.values().iterator().next();
      hashes.put(
          ref,
          FormInputHash.issueHash(
              first.sourceConfigId(),
              first.sourceInstance(),
              first.canonicalUrl(),
              ref.projectId(),
              first.resourceType(),
              Long.toString(ref.issueIid()),
              sides));
    }
    captureService.bindEvidence(target.publicationId(), evidence);
    captureService.completeManifest(target.publicationId());
    return new BuildInputs(
        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(hashes)), evidence.size());
  }

  /**
   * 计算议题当前输入签名与业务快照。
   *
   * <p>先读取已提交证据 H1，再读取回复业务值，最后重新读取证据 H2；H1 与 H2 不一致说明读取期间
   * 来源、权威、集合或 head 已推进，返回 {@code PENDING} 等待原事实链收敛，绝不返回旧签名搭配
   * 新原因的组合。只读权威/集合/head 修订，不读取业务值以外的写状态。
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
      List<FormIssueInputResolver.ScopeRow> scopes = resolver.loadScopes(sourceInstance);
      Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>>
          authorities = resolver.loadAuthorities(sourceInstance, List.of(ref), scopes);
      Map<String, FormIssueInputResolver.AuthorityRow> byTemplate =
          authorities.getOrDefault(ref, Map.of());
      if (byTemplate.isEmpty()) {
        return new Snapshot(
            resolver.hasCoveringScope(scopes, ref) ? Snapshot.Status.PENDING : Snapshot.Status.NONE,
            null,
            null);
      }
      Map<Long, FormIssueInputResolver.ScopeRow> scopesById = new HashMap<>();
      for (FormIssueInputResolver.ScopeRow scope : scopes) {
        scopesById.put(scope.scopeId(), scope);
      }
      String before = evidenceHash(sourceInstance, ref, byTemplate, scopesById);
      if (before == null) {
        return new Snapshot(Snapshot.Status.PENDING, null, null);
      }
      IssueBusinessInput input =
          resolver.resolveAll(sourceInstance, List.of(ref)).inputs().get(ref);
      String after = evidenceHash(sourceInstance, ref, byTemplate, scopesById);
      if (after == null || !before.equals(after)) {
        return new Snapshot(Snapshot.Status.PENDING, null, null);
      }
      IssueBusinessInput.ReplySnapshot reply =
          input == null ? IssueBusinessInput.ReplySnapshot.absent() : input.reply();
      return new Snapshot(Snapshot.Status.OK, before, reply);
    } catch (FormConflictException error) {
      return new Snapshot(Snapshot.Status.PENDING, null, null);
    }
  }

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
    Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> heads =
        loadFormHeads(sourceInstance, Map.of(ref, byTemplate));
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

  /** 当前构建的 worker 身份；同一候选内重复冻结可复用捕获，其他候选不能复用。 */
  static String workerOf(FactRevisionTarget target) {
    return "form-issue-build-" + target.publicationId();
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
