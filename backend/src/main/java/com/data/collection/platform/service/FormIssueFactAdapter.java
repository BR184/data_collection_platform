package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.service.form.FormHeadCaptureService;
import com.data.collection.platform.service.form.FormIssueLinkService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 表单输入接入旧引擎的事实批次事务；不建立平台候选版本或改动同步水位。 */
@Service
public class FormIssueFactAdapter {
  private final JdbcTemplate jdbc;
  private final FormIssueAuthorityRegistrar registrar;
  private final FormIssueInputResolver resolver;
  private final FormIssueBuildInputService inputs;
  private final FormHeadCaptureService captures;
  private final FormIssueLinkService links;
  private final ApplicationEventPublisher events;

  public FormIssueFactAdapter(JdbcTemplate jdbc, FormIssueAuthorityRegistrar registrar,
      FormIssueInputResolver resolver, FormIssueBuildInputService inputs,
      FormHeadCaptureService captures, FormIssueLinkService links, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.registrar = registrar;
    this.resolver = resolver;
    this.inputs = inputs;
    this.captures = captures;
    this.links = links;
    this.events = events;
  }

  /** 本批次冻结的证据；只可在准备它的同一事务内结算。 */
  public record Batch(String sourceInstance, List<IssueFact> facts,
      List<FormHeadCaptureService.InputEvidence> evidence) {}

  /**
   * 持有范围及head锁后读取表单覆盖业务列，原评论事实保持原输入。
   * 调用者必须在同一事务内写入这些事实并调用complete，任一失败整体回滚。
   */
  public List<Batch> prepare(List<IssueFact> facts) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("表单事实接入必须位于事实批次事务内");
    }
    Map<String, List<IssueFact>> bySource = new java.util.TreeMap<>();
    for (IssueFact fact : facts) {
      bySource.computeIfAbsent(fact.getSourceInstance(), ignored -> new ArrayList<>()).add(fact);
    }
    List<Batch> result = new ArrayList<>();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    String worker = "form-fact-batch-" + UUID.randomUUID();
    for (Map.Entry<String, List<IssueFact>> entry : bySource.entrySet()) {
      String source = entry.getKey();
      List<FormIssueInputResolver.ScopeRow> scopes = resolver.loadScopes(source);
      if (scopes.isEmpty()) { continue; }
      Map<FormIssueInputResolver.IssueRef, LocalDateTime> refs = new LinkedHashMap<>();
      for (IssueFact fact : entry.getValue()) {
        if (fact.getProjectId() != null && fact.getIssueIid() != null) {
          refs.put(new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid()), fact.getCreatedAtSource());
        }
      }
      registrar.registerMissing(source, refs, now);
      for (Long scopeId : scopes.stream().map(FormIssueInputResolver.ScopeRow::scopeId).distinct().sorted().toList()) {
        jdbc.queryForList("select id from form_template_scopes where id = ? for share", scopeId);
      }
      Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>> authorities =
          resolver.loadAuthorities(source, List.copyOf(refs.keySet()), scopes);
      List<FormHeadCaptureService.InputEvidence> evidence = new ArrayList<>();
      List<FormIssueInputResolver.AuthorityRow> formSides = authorities.values().stream()
          .flatMap(value -> value.values().stream()).filter(value -> "FORM".equals(value.authority()))
          .sorted(Comparator.comparingLong(FormIssueInputResolver.AuthorityRow::scopeId)
              .thenComparingLong(FormIssueInputResolver.AuthorityRow::resourceId)).toList();
      for (FormIssueInputResolver.AuthorityRow side : formSides) {
        Long headId = jdbc.queryForObject("""
            select id from form_change_heads where scope_id = ? and source_config_id = ?
              and project_id = ? and resource_type = 'issue' and resource_id = ? and template_key = ? for update
            """, Long.class, side.scopeId(), side.sourceConfigId(), side.projectId(), side.resourceId(), side.templateKey());
        evidence.add(captures.ensureEvidence(side.scopeId(), headId, worker, now));
      }
      FormIssueInputResolver.Resolution resolution = resolver.resolveAll(source, refs.keySet());
      if (!resolution.pendingRegistration().isEmpty()) {
        throw new FormConflictException("表单权威未完整登记，拒绝写入不完整事实");
      }
      Map<FormIssueInputResolver.IssueRef, String> hashes = inputs.frozenHashes(source, authorities, scopes);
      for (IssueFact fact : entry.getValue()) {
        if (fact.getProjectId() == null || fact.getIssueIid() == null) { continue; }
        IssueBusinessInput input = resolution.inputs().get(new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid()));
        if (input != null) {
          IssueBusinessProjector.apply(fact, input, labels(fact), now);
        }
        fact.setFormInputHash(hashes.get(new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid())));
      }
      result.add(new Batch(source, entry.getValue(), List.copyOf(evidence)));
    }
    return List.copyOf(result);
  }

  /** 在事实写入后同事务结算证据和链接登记，外部发送仅在提交后发生。 */
  public void complete(List<Batch> batches) {
    for (Batch batch : batches) {
      captures.settleEvidence(batch.evidence());
      List<Object[]> hashes = batch.facts().stream().map(fact -> new Object[] {
          fact.getFormInputHash(), fact.getSourceSystem(), fact.getSourceInstance(), fact.getProjectId(), fact.getIssueId()}).toList();
      jdbc.batchUpdate("update issue_fact set form_input_hash = ? where source_system = ? and source_instance = ? and project_id = ? and issue_id = ?", hashes);
      links.registerPublishedIssues(batch.sourceInstance(), batch.facts().stream().map(IssueFact::getIssueId).toArray(Long[]::new));
      if (batch.evidence().stream().anyMatch(FormHeadCaptureService.InputEvidence::captured)) {
        events.publishEvent(new FormFactPublicationEvent(batch.sourceInstance()));
      }
    }
    if (!batches.isEmpty()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { links.dispatchPendingAsync(); }
      });
    }
  }

  private static List<String> labels(IssueFact fact) {
    return fact.getLabelNames() == null ? List.of() : Arrays.stream(fact.getLabelNames().split(","))
        .map(String::trim).filter(value -> !value.isEmpty()).toList();
  }

  /** 定时延期刷新仅重算已消费且签名一致的表单事实；新输入等待正常事实任务消费。 */
  Map<Long, IssueSlaRules.Evaluation> delayEvaluations(String source, List<IssueFact> facts, LocalDateTime now) {
    List<FormIssueInputResolver.ScopeRow> scopes = resolver.loadScopes(source);
    if (scopes.isEmpty()) { return Map.of(); }
    for (Long scopeId : scopes.stream().map(FormIssueInputResolver.ScopeRow::scopeId).distinct().sorted().toList()) {
      jdbc.queryForList("select id from form_template_scopes where id = ? for share", scopeId);
    }
    List<FormIssueInputResolver.IssueRef> refs = facts.stream()
        .filter(fact -> fact.getProjectId() != null && fact.getIssueIid() != null)
        .map(fact -> new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid())).distinct().toList();
    Map<FormIssueInputResolver.IssueRef, Map<String, FormIssueInputResolver.AuthorityRow>> authorities = resolver.loadAuthorities(source, refs, scopes);
    for (FormIssueInputResolver.AuthorityRow side : authorities.values().stream().flatMap(value -> value.values().stream())
        .filter(value -> "FORM".equals(value.authority())).sorted(Comparator.comparingLong(FormIssueInputResolver.AuthorityRow::scopeId)
            .thenComparingLong(FormIssueInputResolver.AuthorityRow::resourceId)).toList()) {
      jdbc.queryForList("select id from form_change_heads where scope_id = ? and project_id = ? and resource_id = ? for share",
          side.scopeId(), side.projectId(), side.resourceId());
    }
    Map<FormIssueInputResolver.IssueRef, String> hashes = inputs.frozenHashes(source, authorities, scopes);
    FormIssueInputResolver.Resolution resolution = resolver.resolveAll(source, refs);
    Map<Long, IssueSlaRules.Evaluation> result = new LinkedHashMap<>();
    for (IssueFact fact : facts) {
      if (fact.getProjectId() == null || fact.getIssueIid() == null) { continue; }
      FormIssueInputResolver.IssueRef ref = new FormIssueInputResolver.IssueRef(fact.getProjectId(), fact.getIssueIid());
      IssueBusinessInput input = resolution.inputs().get(ref);
      if (input == null) { continue; }
      if (!java.util.Objects.equals(fact.getFormInputHash(), hashes.get(ref))) {
        result.put(fact.getId(), new IssueSlaRules.Evaluation(Boolean.TRUE.equals(fact.getResponseDelayed()),
            Boolean.TRUE.equals(fact.getResolveDelayed()), fact.getResolveDeadlineAt(), fact.getResolveSlaDays() == null ? 18 : fact.getResolveSlaDays()));
      } else {
        result.put(fact.getId(), IssueBusinessProjector.evaluate(fact, input, labels(fact), now));
      }
    }
    return result;
  }
}
