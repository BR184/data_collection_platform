package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 把来源无关的议题业务输入投影到事实列。
 *
 * <p>首次事实会写入响应/回复业务列与延期判定；周期重算只重新计算延期三列，不重写其它业务列。
 * 两种调用共用同一份 {@link IssueSlaRules.Evaluation}，避免首次与周期口径分叉。
 */
final class IssueBusinessProjector {
  private IssueBusinessProjector() {}

  /**
   * 用表单输入覆盖首次事实的业务列与延期判定。
   *
   * @param fact 待写入的事实行
   * @param input 表单业务输入；空输入表示尚未填写，业务列为空而不是回退评论
   * @param labels 当前来源标签
   * @param now 判定时刻
   */
  static void apply(
      IssueFact fact, IssueBusinessInput input, List<String> labels, LocalDateTime now) {
    IssueSlaRules.Evaluation evaluation = evaluate(fact, input, labels, now);
    fact.setHasResponse(input.hasResponse());
    fact.setResearchTemplateTime(input.firstResponseAt());
    fact.setPlannedResolutionAt(input.plannedResolutionAt());
    fact.setPlannedResolutionText(input.plannedResolutionText());
    fact.setPlannedMergeVersionBranch(input.plannedMergeVersionBranch());
    fact.setFixUser(input.fixUser());
    fact.setResolveDeadlineAt(evaluation.resolveDeadlineAt());
    fact.setResolveSlaDays(evaluation.resolveSlaDays());
    fact.setResponseDelayed(evaluation.responseDelayed());
    fact.setResponseOverdue(evaluation.responseDelayed());
    fact.setResolveDelayed(evaluation.resolveDelayed());
  }

  /**
   * 只计算延期判定，供周期重算使用。
   *
   * @param fact 当前事实行
   * @param input 表单业务输入
   * @param labels 当前来源标签
   * @param now 判定时刻
   * @return 三列判定与解决期限；不修改事实行
   */
  static IssueSlaRules.Evaluation evaluate(
      IssueFact fact, IssueBusinessInput input, List<String> labels, LocalDateTime now) {
    boolean open = !"closed".equalsIgnoreCase(fact.getIssueState());
    return IssueSlaRules.evaluate(
        input,
        labels,
        Boolean.TRUE.equals(fact.getFixed()),
        fact.getCreatedAtSource(),
        fact.getPriorityLevel(),
        now,
        open);
  }
}
