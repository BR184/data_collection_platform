package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueFactRecordFilterGroupSupportTest {

  @Test
  void test_combinedStatus_normalFilter_matchesSingleMember() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND", List.of(new StatisticFilterCondition("bugStatus", "eq", "申请延期", null)));

    assertThat(IssueFactRecordFilterGroupSupport.matches(record("历史遗留、申请延期"), filterGroup))
        .isTrue();
  }

  @Test
  void test_combinedStatus_labelGroup_matchesMemberIntersection() {
    StatisticFilterCondition condition =
        new StatisticFilterCondition(
            "bugStatus",
            "intersects",
            null,
            null,
            "LABEL_GROUP",
            8L,
            "状态组",
            List.of("历史遗留"));
    StatisticFilterGroup filterGroup = new StatisticFilterGroup("AND", List.of(condition));

    assertThat(IssueFactRecordFilterGroupSupport.matches(record("历史遗留、申请延期"), filterGroup))
        .isTrue();
  }

  @Test
  void test_combinedStatus_labelGroup_notIntersection_excludesOnlyMatchingMembers() {
    StatisticFilterCondition condition =
        new StatisticFilterCondition(
            "bugStatus",
            "notIntersects",
            null,
            null,
            "LABEL_GROUP",
            8L,
            "状态组",
            List.of("申请延期"));
    StatisticFilterGroup filterGroup = new StatisticFilterGroup("AND", List.of(condition));

    assertThat(IssueFactRecordFilterGroupSupport.matches(record("历史遗留、申请延期"), filterGroup))
        .isFalse();
  }

  @Test
  void test_customerIssueFields_matchDisplayedFactValues() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition("testingPhase", "eq", "新增需求", null),
                new StatisticFilterCondition("fixUser", "eq", "李四", null),
                new StatisticFilterCondition("delayCause", "eq", "需求变更", null)));

    assertThat(
            IssueFactRecordFilterGroupSupport.matchesCustomerIssue(
                record("待处理", "新增需求", "李四", "需求变更"), filterGroup))
        .isTrue();
  }

  @Test
  void test_customerIssueDelayCauseFilter_matchesOneMemberInsideCombinedValue() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND", List.of(new StatisticFilterCondition("delayCause", "eq", "技术卡点", null)));

    assertThat(
            IssueFactRecordFilterGroupSupport.matchesCustomerIssue(
                record("待处理", "", "", "方案卡点&技术卡点"), filterGroup))
        .isTrue();
  }

  @Test
  void test_customerIssueTestingPhase_matchesUnspecifiedDisplayValueAgainstEmptyFact() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition(
                    "testingPhase",
                    "eq",
                    CustomerIssueTestingPhaseSupport.UNSPECIFIED_DISPLAY_VALUE,
                    null)));

    assertThat(IssueFactRecordFilterGroupSupport.matchesCustomerIssue(record("待处理"), filterGroup))
        .isTrue();
  }

  private IssueFactRecord record(String bugStatus) {
    return record(bugStatus, "", "", "");
  }

  private IssueFactRecord record(
      String bugStatus, String testingPhase, String fixUser, String delayCause) {
    LocalDateTime now = LocalDateTime.of(2026, 7, 22, 10, 0);
    return new IssueFactRecord(
        325L,
        "CC_PRODUCT",
        9001L,
        67,
        "组合状态议题",
        "opened",
        testingPhase,
        "",
        "LEVEL2",
        "P2",
        bugStatus,
        "缺陷",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R4",
        "张三",
        "李四",
        fixUser,
        List.of("工程图"),
        "",
        List.of(),
        false,
        "",
        delayCause,
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(1),
        now,
        null);
  }
}
