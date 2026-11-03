package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 把来源无关的议题业务输入投影到事实列。
 *
 * <p>响应与回复分别受持久权威约束：采用表单的一侧整份覆盖对应业务列，未采用的一侧保持评论结果。
 * 原因与模板合规只在回复采用表单时按最新有效回复的稳定 ID 集合重算；基础标签/模块规则保持原值。
 * 首次事实与周期重算共用同一判定，避免口径分叉。
 */
final class IssueBusinessProjector {
  private IssueBusinessProjector() {}

  /**
   * 用权威输入覆盖事实对应侧的Business列与延期判定。
   *
   * @param fact 待写入的事实行
   * @param input 已按持久权威构造的输入；两侧都未采用时调用方不应进入本方法
   * @param labels 当前来源标签
   * @param now 判定时刻
   */
  static void apply(
      IssueFact fact, IssueBusinessInput input, List<String> labels, LocalDateTime now) {
    if (input.responseAdopted()) {
      applyResponse(fact, input.response());
    }
    if (input.replyAdopted()) {
      applyReply(fact, input.reply(), input.responseAdopted());
    }
    IssueSlaRules.Evaluation evaluation = evaluate(fact, input, labels, now);
    fact.setResolveDeadlineAt(evaluation.resolveDeadlineAt());
    fact.setResolveSlaDays(evaluation.resolveSlaDays());
    fact.setResponseDelayed(evaluation.responseDelayed());
    fact.setResponseOverdue(evaluation.responseDelayed());
    fact.setResolveDelayed(evaluation.resolveDelayed());
  }

  /**
   * 只计算延期判定，供周期重算使用；业务列必须来自同一次已发布输入。
   *
   * @param fact 当前已发布事实行
   * @param input 与发布事实同一快照的权威输入
   * @param labels 当前来源标签
   * @param now 判定时刻
   * @return 三列判定与解决期限；不修改事实行
   */
  static IssueSlaRules.Evaluation evaluate(
      IssueFact fact, IssueBusinessInput input, List<String> labels, LocalDateTime now) {
    boolean open = !"closed".equalsIgnoreCase(fact.getIssueState());
    LocalDateTime createdAt = fact.getCreatedAtSource();
    LocalDateTime plannedResolutionAt =
        input.responseAdopted()
            ? input.response().plannedResolutionAt()
            : fact.getPlannedResolutionAt();
    boolean responsePlanExempt =
        input.responseAdopted()
            ? input.response().planExempt()
            : IssueSlaRules.isResponsePlanExempt(fact.getRawPayload());
    boolean hasReply =
        input.replyAdopted()
            ? input.reply().present()
            : IssueFactNormalizationRules.hasFixCaseNote(fact.getRawPayload());
    // 表单回复已接管该侧时，旧评论修复模板不再作为解决豁免证据。
    boolean hasFixCaseNote =
        !input.replyAdopted()
            && IssueFactNormalizationRules.hasFixCaseNote(fact.getRawPayload());
    // 响应侧采用表单时才由表单计划推导期限；否则沿用已发布事实的评论期限，不混算。
    LocalDateTime deadline =
        input.responseAdopted()
            ? IssueSlaRules.resolveDeadline(createdAt, plannedResolutionAt)
            : fact.getResolveDeadlineAt();
    int resolveSlaDays =
        input.responseAdopted()
            ? IssueSlaRules.resolveSlaDays(createdAt, plannedResolutionAt)
            : (fact.getResolveSlaDays() == null ? 18 : fact.getResolveSlaDays());
    boolean responseDelayed =
        open
            && !(hasReply || responsePlanExempt)
            && IssueSlaRules.isResponseDelayedByClock(
                labels, createdAt, fact.getPriorityLevel(), now);
    boolean resolveDelayed =
        open
            && IssueSlaRules.isResolveDelayedWithStatusMembers(
                labels,
                IssueStatusMembers.parse(fact.getBugStatus()),
                hasReply,
                hasFixCaseNote,
                deadline,
                now);
    return new IssueSlaRules.Evaluation(
        responseDelayed, resolveDelayed, deadline, resolveSlaDays);
  }

  private static void applyResponse(
      IssueFact fact, IssueBusinessInput.ResponseSnapshot response) {
    fact.setHasResponse(response.present());
    fact.setResearchTemplateTime(response.firstResponseAt());
    fact.setPlannedResolutionAt(response.plannedResolutionAt());
    fact.setPlannedResolutionText(response.plannedResolutionText());
    fact.setPlannedMergeVersionBranch(response.plannedMergeVersionBranch());
    // 表单响应已按控件与必填校验，不再沿用评论调研模板的“未按要求填写”结论。
    applyIllegal(fact, without(fact, IssueClassificationRules.INVALID_RESEARCH_TEMPLATE));
  }

  private static void applyReply(
      IssueFact fact, IssueBusinessInput.ReplySnapshot reply, boolean responseAdopted) {
    fact.setFixUser(reply.fixUser());
    List<String> reasons =
        without(
            fact,
            IssueClassificationRules.TEMPLATE_NOT_FOLLOWED,
            IssueClassificationRules.NON_UNIQUE_REASON);
    if (responseAdopted) {
      reasons.remove(IssueClassificationRules.INVALID_RESEARCH_TEMPLATE);
    }
    if (reply.present() && reply.distinctCauseCount() != 1) {
      reasons.add(IssueClassificationRules.NON_UNIQUE_REASON);
    }
    fact.setReasonCategory(reply.present() ? reply.canonicalReasonText() : "");
    applyIllegal(fact, reasons);
  }

  private static List<String> without(IssueFact fact, String... removed) {
    List<String> reasons = new ArrayList<>();
    String raw = fact.getIllegalReasons();
    if (raw != null) {
      for (String item : raw.split("\\s*,\\s*")) {
        if (!item.isBlank()) {
          reasons.add(item.trim());
        }
      }
    }
    for (String value : removed) {
      reasons.remove(value);
    }
    return reasons;
  }

  private static void applyIllegal(IssueFact fact, List<String> reasons) {
    fact.setIllegalReasons(String.join(", ", reasons));
    fact.setIllegalReason(reasons.isEmpty() ? null : reasons.getFirst());
    fact.setIllegal(!reasons.isEmpty());
  }
}
