package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssuePlannedMergeBranchMembersTest {

  @Test
  void multipleMembersShouldBeTrimmedDeduplicatedAndMatchedExactly() {
    String rawValue = " CC2026R4 & CC2026R5 & CC2026R4 ";

    assertThat(CustomerIssuePlannedMergeBranchMembers.parse(rawValue))
        .containsExactly("CC2026R4", "CC2026R5");
    assertThat(CustomerIssuePlannedMergeBranchMembers.matchesSelection(rawValue, "cc2026r4"))
        .isTrue();
    assertThat(CustomerIssuePlannedMergeBranchMembers.matchesSelection(rawValue, "CC2026R"))
        .isFalse();
    assertThat(CustomerIssuePlannedMergeBranchMembers.collectMembers(
            List.of(rawValue, "CC2026R6 & CC2026R5")))
        .containsExactly("CC2026R4", "CC2026R5", "CC2026R6");
  }

  @Test
  void mixedSourceDelimitersShouldProduceOrderedDistinctMembers() {
    String rawValue = " dev, 26R1，26R2、26R3 & dev ";

    assertThat(CustomerIssuePlannedMergeBranchMembers.parse(rawValue))
        .containsExactly("dev", "26R1", "26R2", "26R3");
    assertThat(CustomerIssuePlannedMergeBranchMembers.matchesSelection(rawValue, "26r2"))
        .isTrue();
    assertThat(CustomerIssuePlannedMergeBranchMembers.matchesSelection(rawValue, "26R"))
        .isFalse();
  }

  @Test
  void branchNamePunctuationShouldRemainPartOfSingleMember() {
    assertThat(
            CustomerIssuePlannedMergeBranchMembers.parse(
                "crownCAD-Client:release/2026R3_feature.1"))
        .containsExactly("crownCAD-Client:release/2026R3_feature.1");
  }
}
