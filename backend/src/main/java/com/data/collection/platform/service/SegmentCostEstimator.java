package com.data.collection.platform.service;

import org.springframework.stereotype.Service;

@Service
public class SegmentCostEstimator {
  private final long softMemberLimit;
  private final int maxExecutionTimeMs;

  public SegmentCostEstimator() {
    this(10_000, 30_000);
  }

  SegmentCostEstimator(long softMemberLimit, int maxExecutionTimeMs) {
    this.softMemberLimit = softMemberLimit;
    this.maxExecutionTimeMs = maxExecutionTimeMs;
  }

  public SegmentExecutionPlan estimate(SegmentRuleDsl dsl, SegmentExecutionPlan explainedPlan) {
    SegmentExecutionPlan plan =
        new SegmentExecutionPlan(
            explainedPlan.templateName(),
            explainedPlan.estimatedRows(),
            explainedPlan.estimatedMembers(),
            Math.min(explainedPlan.maxExecutionTimeMs(), maxExecutionTimeMs),
            explainedPlan.maxAllowedMembers());
    if (plan.estimatedMembers() > softMemberLimit) {
      throw new IllegalArgumentException(
          "estimated members exceed phase 1 soft limit: " + softMemberLimit);
    }
    return plan;
  }
}
