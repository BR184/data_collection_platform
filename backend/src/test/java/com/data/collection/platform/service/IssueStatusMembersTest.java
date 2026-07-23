package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IssueStatusMembersTest {

  @Test
  void test_combinedStatus_parse_returnsOrderedDistinctMembers() {
    assertThat(IssueStatusMembers.parse(" 历史遗留、申请延期，历史遗留,待合并 & 申请延期 "))
        .containsExactly("历史遗留", "申请延期", "待合并");
  }

  @Test
  void test_resolvedStatus_parse_keepsSlashAsOneMember() {
    assertThat(IssueStatusMembers.parse("已修复/完成")).containsExactly("已修复/完成");
  }

  @Test
  void test_combinedStatus_matchesSelection_matchesEachMemberWithoutSplittingIssue() {
    String combinedStatus = "历史遗留、申请延期";

    assertThat(IssueStatusMembers.matchesSelection(combinedStatus, "历史遗留")).isTrue();
    assertThat(IssueStatusMembers.matchesSelection(combinedStatus, "申请延期")).isTrue();
    assertThat(IssueStatusMembers.matchesSelection(combinedStatus, "已修复/完成")).isFalse();
  }

  @Test
  void test_fixedSelection_matchesLegacyResolvedMembers() {
    assertThat(IssueStatusMembers.matchesSelection("已修复/完成", "已修复")).isTrue();
    assertThat(IssueStatusMembers.matchesSelection("待合并", "已修复")).isTrue();
    assertThat(IssueStatusMembers.matchesSelection("未更新", "已修复")).isTrue();
  }

  @Test
  void test_combinedStatus_matchesLabelGroupByMemberIntersection() {
    String combinedStatus = "历史遗留、申请延期";

    assertThat(
            IssueStatusMembers.matchesLabelGroup(
                combinedStatus, "intersects", List.of("申请延期")))
        .isTrue();
    assertThat(
            IssueStatusMembers.matchesLabelGroup(
                combinedStatus, "containsAll", List.of("历史遗留", "申请延期")))
        .isTrue();
    assertThat(
            IssueStatusMembers.matchesLabelGroup(
                combinedStatus, "notIntersects", List.of("未修复")))
        .isTrue();
  }
}
