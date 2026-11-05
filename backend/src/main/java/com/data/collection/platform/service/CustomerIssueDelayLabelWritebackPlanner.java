package com.data.collection.platform.service;

import com.data.collection.platform.entity.IssueFact;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueDelayLabelWritebackPlanner {
  private final CustomerIssueDelayLabelWritebackService writebackService;

  public CustomerIssueDelayLabelWritebackPlanner(CustomerIssueDelayLabelWritebackService writebackService) {
    this.writebackService = writebackService;
  }

  /**
   * 由已发布事实与本轮冻结的回复输入计算写回目标。
   *
   * @param fact 已发布事实；{@code null} 视为无标签
   * @param reply 表单回复快照；{@code null} 表示无表单输入，不管理本期四标签
   * @return 当前标签与合并差异
   */
  CustomerIssueDelayLabelWritebackPlan plan(
      IssueFact fact, IssueBusinessInput.ReplySnapshot reply) {
    List<String> currentLabels = parseLabels(fact == null ? null : fact.getLabelNames());
    boolean deferralManaged = reply != null && reply.deferralTracked();
    String deferralReason = reply == null ? "" : reply.deferralTargetReason();
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        writebackService.labelChange(
            currentLabels,
            fact != null && Boolean.TRUE.equals(fact.getResponseDelayed()),
            fact != null && Boolean.TRUE.equals(fact.getResolveDelayed()),
            deferralManaged,
            deferralReason);
    return new CustomerIssueDelayLabelWritebackPlan(fact, currentLabels, change);
  }

  List<String> parseLabels(String labelNames) {
    if (!StringUtils.hasText(labelNames)) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String part : labelNames.split(",")) {
      if (StringUtils.hasText(part)) {
        labels.add(part.trim());
      }
    }
    return labels;
  }
}
