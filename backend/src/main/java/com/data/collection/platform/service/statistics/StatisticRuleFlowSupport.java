package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import java.util.List;
import java.util.function.Function;

final class StatisticRuleFlowSupport {
  private static final int SAMPLE_LIMIT = 3;

  private StatisticRuleFlowSupport() {
  }

  static <T> StatisticRuleFlowStep step(
      String key,
      String title,
      String description,
      long inputCount,
      List<T> output,
      Function<T, StatisticRuleFlowStepSample> sampleMapper) {
    return step(key, title, description, inputCount, output.size(), output, sampleMapper);
  }

  static <T> StatisticRuleFlowStep step(
      String key,
      String title,
      String description,
      long inputCount,
      long outputCount,
      List<T> sampleItems,
      Function<T, StatisticRuleFlowStepSample> sampleMapper) {
    return new StatisticRuleFlowStep(
        key,
        title,
        description,
        inputCount,
        outputCount,
        samples(sampleItems, sampleMapper));
  }

  static <T> List<StatisticRuleFlowStepSample> samples(
      List<T> items,
      Function<T, StatisticRuleFlowStepSample> sampleMapper) {
    return items.stream().limit(SAMPLE_LIMIT).map(sampleMapper).toList();
  }
}
