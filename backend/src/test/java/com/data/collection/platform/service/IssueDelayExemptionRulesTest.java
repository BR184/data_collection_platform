package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证延期共用豁免优先于空值、非法日期与已经超过的时限。 */
class IssueDelayExemptionRulesTest {
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 4, 1, 0, 0);
  private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 4, 19, 0, 0);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 0, 0);

  @ParameterizedTest
  @MethodSource("exemptLabelsAndInvalidPlans")
  void shouldExemptBothDelaysByTheSameLabelRegardlessOfThePlan(String label, String plan) {
    List<String> labels = List.of(label, "响应已延期", "解决已延期");
    String notes = "# 问题调研情况说明\n## 计划解决时间：" + plan;

    assertThat(IssueFactNormalizationRules.isResponseDelayed(labels, notes, CREATED_AT, "P1", NOW))
        .isFalse();
    assertThat(IssueFactNormalizationRules.isResponseDelayed(labels, notes)).isFalse();
    assertThat(IssueFactNormalizationRules.isResolveDelayed(labels, false, DEADLINE, NOW)).isFalse();
  }

  static Stream<Arguments> exemptLabelsAndInvalidPlans() {
    // 全角状态、裸数据异常、需求和类别：建议已由本地镜像关联核实；其它明确形式为合成边界输入。
    List<String> labels = List.of(
        "状态：申请延期", "状态：未复现", "状态：申请否决", "数据异常", "需求", "类别：建议",
        "申请延期", "设计如此", "状态：设计如此", "申请否决", "未复现", "状态：数据异常",
        "建议", "需求如此", "状态：需求如此", "状态:申请否决", " 状态 : 未复现 ");
    return labels.stream().flatMap(label -> Stream.of("", "待定", "2026.02.30")
        .map(plan -> Arguments.of(label, plan)));
  }

  @Test
  void shouldExemptBothDelaysByAFixTemplateWithoutRequiringAFixedStatus() {
    List<String> labels = List.of("响应已延期", "解决已延期");
    String notes = "### 1、修复状态\n[x] 已解决\n---\n# 问题调研情况说明\n## 计划解决时间：";

    assertThat(IssueFactNormalizationRules.isResponseDelayed(labels, notes, CREATED_AT, "P1", NOW))
        .isFalse();
    assertThat(IssueFactNormalizationRules.isResolveDelayed(
        labels, IssueFactNormalizationRules.hasFixCaseNote(notes), DEADLINE, NOW)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "延期原因：数据异常", "缺陷原因：未复现", "模块：需求", "类别：建议处理", "非未复现",
      "申请否决待确认", "状态：非数据异常", "状态：未复现待确认", "类型：建议", "模块：建议"
  })
  void shouldNotExemptReasonPrefixesNegationsOrUnrelatedLabelGroups(String label) {
    List<String> labels = List.of(label, "响应已延期", "解决已延期");
    String notes = "# 问题调研情况说明\n## 计划解决时间：待定";

    assertThat(IssueFactNormalizationRules.isResponseDelayed(labels, notes, CREATED_AT, "P1", NOW))
        .isTrue();
    assertThat(IssueFactNormalizationRules.isResolveDelayed(labels, false, DEADLINE, NOW)).isTrue();
  }
}
