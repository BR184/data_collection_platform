package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemTestIssueMetricDimensionSupportTest {
  @Test
  void shouldUseTheSameCanonicalDimensionsForChartAndDetailFilters() {
    assertThat(SystemTestIssueMetricDimensionSupport.metricSeverity(
            false, "", "二级缺陷", "建议类"))
        .isEqualTo("SUGGESTION");
    assertThat(SystemTestIssueMetricDimensionSupport.metricSeverity(
            false, "", "LEVEL1", ""))
        .isEqualTo("LEVEL1");
    assertThat(SystemTestIssueMetricDimensionSupport.majorCause(
            "需求问题", "新增理解偏差"))
        .isEqualTo("需求阶段");
    assertThat(SystemTestIssueMetricDimensionSupport.delayCause(
            "", "算法问题导致延期", ""))
        .isEqualTo("算法问题");
    assertThat(SystemTestIssueMetricDimensionSupport.matchesCauseMetric(
            "demand_misunderstand", "", "新增理解偏差"))
        .isTrue();
    assertThat(SystemTestIssueMetricDimensionSupport.rollback(
            false, "修复后出现回退", ""))
        .isTrue();
  }
}
