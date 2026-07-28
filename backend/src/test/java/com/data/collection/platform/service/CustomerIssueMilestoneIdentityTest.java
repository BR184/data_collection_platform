package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerIssueMilestoneIdentityTest {

  @Test
  void test_standard_cc_versions_with_spacing_share_one_business_key() {
    assertThat(CustomerIssueMilestoneIdentity.businessKey("CC2026 R3"))
        .isEqualTo("CC2026R3");
    assertThat(CustomerIssueMilestoneIdentity.businessKey(" cc 2026 r 3 "))
        .isEqualTo("CC2026R3");
  }

  @Test
  void test_non_standard_milestone_preserves_trimmed_identity() {
    assertThat(CustomerIssueMilestoneIdentity.businessKey("  客户专项版本  "))
        .isEqualTo("客户专项版本");
    assertThat(CustomerIssueMilestoneIdentity.businessKey("  ")).isEmpty();
  }
}
