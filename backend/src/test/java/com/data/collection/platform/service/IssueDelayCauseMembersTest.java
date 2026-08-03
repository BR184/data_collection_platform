package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IssueDelayCauseMembersTest {

  @Test
  void labels_normalizeInSourceOrder_andRemoveDuplicateCauses() {
    assertThat(IssueDelayCauseMembers.normalizeLabels(
            List.of("方案卡点", "申请延期", "技术卡点", "方案卡点")))
        .isEqualTo("方案卡点&技术卡点");
  }

  @Test
  void labels_supportLegacyPrefixedCause_andIgnoreUnrelatedLabels() {
    assertThat(IssueDelayCauseMembers.fromLabels(
            List.of("延期原因：资源卡点", "普通标签", "延期原因:数据异常")))
        .containsExactly("资源卡点", "数据异常");
  }

  @Test
  void combinedValue_parseReturnsEveryCauseMember_andKeepsFallbackValue() {
    assertThat(IssueDelayCauseMembers.parse("方案卡点 & 技术卡点&方案卡点"))
        .containsExactly("方案卡点", "技术卡点");
    assertThat(IssueDelayCauseMembers.parse("未设定类别")).containsExactly("未设定类别");
  }

  @Test
  void filters_matchOneMemberInsideCombinedValue() {
    String value = "方案卡点&技术卡点";

    assertThat(IssueDelayCauseMembers.matchesFilter(value, "eq", "技术卡点")).isTrue();
    assertThat(IssueDelayCauseMembers.matchesFilter(value, "ne", "技术卡点")).isFalse();
    assertThat(IssueDelayCauseMembers.matchesLabelGroup(
            value, "containsAll", List.of("方案卡点", "技术卡点")))
        .isTrue();
  }

  @Test
  void filters_matchResolvedMemberCollection_withoutReconstructingCombinedText() {
    List<String> values = List.of("方案卡点", "技术卡点");

    assertThat(IssueDelayCauseMembers.matchesFilter(values, "eq", "技术卡点")).isTrue();
    assertThat(IssueDelayCauseMembers.matchesLabelGroup(
            values, "containsAll", List.of("方案卡点", "技术卡点")))
        .isTrue();
  }
}
