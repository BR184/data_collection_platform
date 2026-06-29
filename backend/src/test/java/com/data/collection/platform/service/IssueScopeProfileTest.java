package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueScopeProfileTest {

  private final SystemTestScopeProfile systemTestScopeProfile = new SystemTestScopeProfile();
  private final CustomerIssueScopeProfile customerIssueScopeProfile =
      new CustomerIssueScopeProfile();

  @Test
  void shouldRecognizeSystemTestScopeFromCrownCadTestingPhaseOnly() {
    IssueScopeContext byPhase =
        new IssueScopeContext(
            9L,
            "CrownCAD",
            null,
            "CC2026R1第一轮系统测试",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("工程图", "P1"));
    IssueScopeContext byLabelOnly =
        new IssueScopeContext(
            9L,
            "CrownCAD",
            null,
            "",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("CC2026R1回归测试", "草图"));
    IssueScopeContext byOtherProject =
        new IssueScopeContext(
            325L,
            "CC_Product",
            null,
            "CC2026R1第一轮系统测试",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("工程图", "P1"));

    assertThat(systemTestScopeProfile.matches(byPhase)).isTrue();
    assertThat(systemTestScopeProfile.matches(byLabelOnly)).isFalse();
    assertThat(systemTestScopeProfile.matches(byOtherProject)).isFalse();
  }

  @Test
  void shouldRecognizeCustomerIssueScopeFromCcProductProjectAndDate() {
    IssueScopeContext customerIssue =
        new IssueScopeContext(
            325L,
            "CC_Product",
            "CC2026R1-M1",
            "",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("工程图", "P2"));

    assertThat(customerIssueScopeProfile.matches(customerIssue)).isTrue();
  }

  @Test
  void shouldNotRecognizeCustomerIssueScopeFromProjectNameOnly() {
    IssueScopeContext projectNameOnly =
        new IssueScopeContext(
            9L,
            "CC_Product",
            "CC2026R1-M1",
            "",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("工程图", "P2"));

    assertThat(customerIssueScopeProfile.matches(projectNameOnly)).isFalse();
  }

  @Test
  void shouldKeepCustomerIssueScopeWhenCcProductAlsoHasSystemTestLabel() {
    IssueScopeContext overlapped =
        new IssueScopeContext(
            325L,
            "CC_Product",
            "CC2026R1-M1",
            "CC2026R1第一轮系统测试",
            "",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            List.of("工程图", "P2"));

    assertThat(customerIssueScopeProfile.matches(overlapped)).isTrue();
  }

  @Test
  void shouldRejectCustomerIssueBeforeConfiguredStartDate() {
    IssueScopeContext beforeStart =
        new IssueScopeContext(
            325L,
            "CC_Product",
            "CC2025R2-M1",
            "",
            "",
            LocalDateTime.of(2025, 12, 31, 23, 59),
            List.of("工程图"));

    assertThat(customerIssueScopeProfile.matches(beforeStart)).isFalse();
  }
}
