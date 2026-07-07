package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.IssueFact;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayLabelWritebackPlannerTest {
  private final CustomerIssueDelayLabelWritebackPlanner planner =
      new CustomerIssueDelayLabelWritebackPlanner(
          new CustomerIssueDelayLabelWritebackService(HttpClient.newHttpClient(), true));

  @Test
  void shouldOnlyPlanChangesForTwoDelayLabels() {
    IssueFact fact = new IssueFact();
    fact.setLabelNames("模块：平台, 响应已延期, P1");
    fact.setResponseDelayed(false);
    fact.setResolveDelayed(true);

    CustomerIssueDelayLabelWritebackPlan plan = planner.plan(fact);

    assertThat(plan.change().addLabels()).containsExactly("解决已延期");
    assertThat(plan.change().removeLabels()).containsExactly("响应已延期");
    assertThat(plan.currentLabels()).containsExactly("模块：平台", "响应已延期", "P1");
  }
}
