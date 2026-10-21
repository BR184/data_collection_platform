package com.data.collection.platform.domain.customerissue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 客户问题共享纯规则特征测试（S01/S03）。
 *
 * <p>锁定 F/P/A、需求标签 OR、效率舍入与延期资格，供规则迁移前后对照。
 */
class CustomerIssueMetricRulesTest {

  @Test
  void fixedBySummary_matchesLegacyTokens() {
    assertTrue(CustomerIssueMetricRules.isFixedBySummary("状态：已修复"));
    assertTrue(CustomerIssueMetricRules.isFixedBySummary("待合并"));
    assertTrue(CustomerIssueMetricRules.isFixedBySummary("未更新"));
    assertFalse(CustomerIssueMetricRules.isFixedBySummary("申请延期"));
    assertFalse(CustomerIssueMetricRules.isFixedBySummary("未复现"));
    assertFalse(CustomerIssueMetricRules.isFixedBySummary(null));
  }

  @Test
  void priorityFixed_differsFromOverallFixed() {
    assertTrue(CustomerIssueMetricRules.isPriorityFixed("状态：已修复/完成", false));
    assertTrue(CustomerIssueMetricRules.isPriorityFixed("未复现", false));
    assertTrue(CustomerIssueMetricRules.isPriorityFixed("任意", true));
    assertFalse(CustomerIssueMetricRules.isPriorityFixed("待合并", false));
    assertFalse(CustomerIssueMetricRules.isPriorityFixed("未更新", false));
  }

  @Test
  void appliedDelay_isIndependentState() {
    assertTrue(CustomerIssueMetricRules.hasAppliedDelay("申请延期"));
    assertTrue(CustomerIssueMetricRules.hasAppliedDelay("状态：申请延期"));
    assertFalse(CustomerIssueMetricRules.hasAppliedDelay("已修复"));
  }

  @Test
  void calibrationStates_areExactMembersNotSubstrings() {
    assertTrue(CustomerIssueMetricRules.hasDataException(List.of("数据异常")));
    assertTrue(CustomerIssueMetricRules.hasDataException(List.of("已修复/完成", "数据异常")));
    assertTrue(CustomerIssueMetricRules.hasNotReproduced(List.of("未复现")));
    // 精确成员匹配：包含同名字样的其它状态不得误命中。
    assertFalse(CustomerIssueMetricRules.hasDataException(List.of("疑似数据异常问题")));
    assertFalse(CustomerIssueMetricRules.hasNotReproduced(List.of("已修复/完成", "待合并")));
    assertFalse(CustomerIssueMetricRules.hasDataException(List.of()));
    assertFalse(CustomerIssueMetricRules.hasDataException(null));
  }

  @Test
  void calibrationPopulation_removesUnionOnceAndKeepsAppliedDelay() {
    assertTrue(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("处理中")));
    // 申请延期不额外剔除，是否留在 V 只由两类剔除状态决定。
    assertTrue(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("申请延期")));
    assertTrue(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("已修复/完成", "待合并")));
    assertFalse(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("数据异常")));
    assertFalse(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("未复现")));
    // 两类同时命中只剔除一次：资格是单一布尔，不存在第二次扣除。
    assertFalse(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of("数据异常", "未复现")));
    // 无状态成员不构成剔除资格，议题仍留在 V 内。
    assertTrue(CustomerIssueMetricRules.keepsCalibrationPopulation(List.of()));
  }

  @Test
  void calibration_doesNotRewriteLegacyFixPredicates() {
    // 普通判据保持原语义：P 仍认“未复现”，校准只在其之前移除议题。
    assertTrue(CustomerIssueMetricRules.isPriorityFixed("未复现", false));
    assertFalse(CustomerIssueMetricRules.isFixedBySummary("数据异常"));
    assertFalse(CustomerIssueMetricRules.isFixedBySummary("未复现"));
  }

  @Test
  void customerRequirement_exactLabelOr() {
    assertTrue(CustomerRequirementRules.isCustomerRequirement(List.of("需求")));
    assertTrue(CustomerRequirementRules.isCustomerRequirement(List.of("类别：建议")));
    assertTrue(CustomerRequirementRules.isCustomerRequirement(List.of("需求", "类别：建议")));
    assertFalse(CustomerRequirementRules.isCustomerRequirement(List.of("类型：建议")));
    assertFalse(CustomerRequirementRules.isCustomerRequirement(List.of("需求如此")));
    assertFalse(CustomerRequirementRules.isCustomerRequirement(List.of("新增需求问题")));
    assertFalse(CustomerRequirementRules.derive(List.of("需求"), false));
    assertTrue(CustomerRequirementRules.derive(List.of("类别：建议"), true));
  }

  @Test
  void efficiency_samplesAndRounding() {
    LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
    LocalDateTime researched = created.plusHours(27);
    assertTrue(CustomerIssueEfficiencyRules.hasResponseCycle(created, researched));
    assertFalse(CustomerIssueEfficiencyRules.hasResponseCycle(created, null));
    assertEquals(27L, CustomerIssueEfficiencyRules.responseCycleHours(created, researched));
    assertEquals(0L, CustomerIssueEfficiencyRules.responseCycleHours(created, null));

    LocalDateTime fixed = created.plusHours(36);
    assertTrue(CustomerIssueEfficiencyRules.hasResolutionCycle(created, fixed, true));
    assertFalse(CustomerIssueEfficiencyRules.hasResolutionCycle(created, fixed, false));
    assertFalse(CustomerIssueEfficiencyRules.hasResolutionCycle(created, null, true));
    assertEquals(
        0,
        new BigDecimal("1.500000").compareTo(
            CustomerIssueEfficiencyRules.resolutionCycleDays(created, fixed)));

    assertEquals(0L, CustomerIssueEfficiencyRules.averageResponseHours(List.of()));
    assertEquals(2L, CustomerIssueEfficiencyRules.averageResponseHours(List.of(1L, 2L)));
    assertEquals(
        0,
        new BigDecimal("0.0").compareTo(CustomerIssueEfficiencyRules.averageResolutionDays(List.of())));
    assertEquals(
        0,
        new BigDecimal("1.1")
            .compareTo(
                CustomerIssueEfficiencyRules.averageResolutionDays(
                    List.of(new BigDecimal("1.04"), new BigDecimal("1.06")))));
  }

  @Test
  void delayEligibility_requiresRegularOpenAndDelay() {
    assertTrue(
        CustomerIssueDelayRules.isDelayEligible(true, false, true, true, false));
    assertFalse(
        CustomerIssueDelayRules.isDelayEligible(false, false, true, true, true));
    assertFalse(
        CustomerIssueDelayRules.isDelayEligible(true, true, true, true, true));
    assertFalse(
        CustomerIssueDelayRules.isDelayEligible(true, false, false, true, true));
    assertFalse(
        CustomerIssueDelayRules.isDelayEligible(true, false, true, false, false));
    assertTrue(CustomerIssueDelayRules.hasLegacyPriorityBucket("P1"));
    assertFalse(CustomerIssueDelayRules.hasLegacyPriorityBucket(null));
    assertFalse(CustomerIssueDelayRules.hasLegacyPriorityBucket("P4"));
    // 旧延期页按归一化文本包含匹配，不改成等值判断。
    assertTrue(CustomerIssueDelayRules.hasLegacyPriorityBucket("状态：P2"));
    assertEquals("P3", CustomerIssueDelayRules.priorityBucket("p3-紧急"));
    assertEquals("", CustomerIssueDelayRules.priorityBucket("未设定紧急程度"));
    assertTrue(CustomerIssueDelayRules.hasGitLabApiError("GitLab 接口报错", null));
    assertTrue(CustomerIssueDelayRules.hasGitLabApiError(null, List.of("GitLab 接口报错")));
    assertFalse(CustomerIssueDelayRules.hasGitLabApiError("其它非法", List.of("其它非法")));
  }
}
