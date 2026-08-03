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
}
