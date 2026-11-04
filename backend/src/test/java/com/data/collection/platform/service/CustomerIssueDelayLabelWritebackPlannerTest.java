package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayLabelWritebackPlannerTest {
  private final CustomerIssueDelayLabelWritebackPlanner planner =
      new CustomerIssueDelayLabelWritebackPlanner(new CustomerIssueDelayLabelWritebackService());

  @Test
  void shouldOnlyPlanChangesForTwoDelayLabels() {
    IssueFact fact = new IssueFact();
    fact.setLabelNames("模块：平台, 响应已延期, P1");
    fact.setResponseDelayed(false);
    fact.setResolveDelayed(true);

    CustomerIssueDelayLabelWritebackPlan plan = planner.plan(fact, null);

    assertThat(plan.change().addLabels()).containsExactly("解决已延期");
    assertThat(plan.change().removeLabels()).containsExactly("响应已延期");
    assertThat(plan.currentLabels()).containsExactly("模块：平台", "响应已延期", "P1");
  }

  @Test
  void shouldPlanDeferralLabelsFromLatestTrackedReply() {
    IssueFact fact = new IssueFact();
    fact.setLabelNames("模块：平台");

    CustomerIssueDelayLabelWritebackPlan plan =
        planner.plan(fact, reply("DEFERRED", "TECHNICAL_BLOCKER", true, true));

    assertThat(plan.change().addLabels()).containsExactly("状态：申请延期", "技术卡点");
    assertThat(plan.change().removeLabels()).isEmpty();
  }

  @Test
  void shouldClearFourDeferralLabelsWhenTrackedTopicHasNoValidReply() {
    IssueFact fact = new IssueFact();
    fact.setLabelNames("状态：申请延期, 技术卡点, 资源卡点, 数据异常, 模块：平台");

    CustomerIssueDelayLabelWritebackPlan plan =
        planner.plan(fact, IssueBusinessInput.ReplySnapshot.absent(true));

    assertThat(plan.change().addLabels()).isEmpty();
    assertThat(plan.change().removeLabels())
        .containsExactly("状态：申请延期", "技术卡点", "资源卡点", "数据异常");
  }

  @Test
  void shouldLeaveDeferralLabelsWhenOnlyOldVersionRepliesExist() {
    IssueFact fact = new IssueFact();
    fact.setLabelNames("状态：申请延期, 技术卡点, 模块：平台");

    CustomerIssueDelayLabelWritebackPlan plan =
        planner.plan(fact, reply("DEFERRED", "RESOURCE_BLOCKER", false, false));

    assertThat(plan.hasChanges()).isFalse();
    assertThat(plan.currentLabels()).contains("状态：申请延期", "技术卡点");
  }

  private static IssueBusinessInput.ReplySnapshot reply(
      String fixStatus, String reason, boolean fieldPresent, boolean tracked) {
    return new IssueBusinessInput.ReplySnapshot(
        true,
        21L,
        LocalDateTime.of(2026, 11, 12, 9, 0),
        "account-a",
        "甲",
        fixStatus,
        List.of("standard_error"),
        List.of("编码规范错误"),
        "原因",
        "方案",
        false,
        List.of("NONE"),
        null,
        null,
        "NOT_APPLICABLE",
        false,
        null,
        "NOT_APPLICABLE",
        null,
        reason,
        fixStatus,
        fieldPresent,
        tracked);
  }
}
