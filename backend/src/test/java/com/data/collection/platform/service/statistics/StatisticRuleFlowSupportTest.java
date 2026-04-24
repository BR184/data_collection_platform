package com.data.collection.platform.service.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticRuleFlowSupportTest {
  @Test
  void stepShouldKeepInputCountAndLimitSamples() {
    StatisticRuleFlowStep step =
        StatisticRuleFlowSupport.step(
            "k",
            "t",
            "d",
            5,
            List.of("a", "b", "c", "d"),
            value -> new StatisticRuleFlowStepSample(value, value));

    assertEquals(5, step.inputCount());
    assertEquals(4, step.outputCount());
    assertEquals(3, step.samples().size());
    assertEquals("a", step.samples().get(0).label());
  }

  @Test
  void stepShouldAllowOutputCountDifferentFromSampleSourceSize() {
    StatisticRuleFlowStep step =
        StatisticRuleFlowSupport.step(
            "expand",
            "expand title",
            "expand desc",
            2,
            5,
            List.of("a", "b", "c", "d"),
            value -> new StatisticRuleFlowStepSample(value, value));

    assertEquals(2, step.inputCount());
    assertEquals(5, step.outputCount());
    assertEquals(3, step.samples().size());
    assertEquals("a", step.samples().get(0).label());
  }
}
