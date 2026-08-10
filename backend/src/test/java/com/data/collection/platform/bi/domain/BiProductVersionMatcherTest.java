package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BiProductVersionMatcherTest {
  private final BiProductVersionMatcher matcher = new BiProductVersionMatcher();

  @Test
  void matchesNormalizedAndCombinedProductVersionNames() {
    assertThat(matcher.matches("CrownCAD 2026 R4（正式版）", "CC2026R4")).isTrue();
    assertThat(matcher.matches("CC2025R1&R2", "CC2025R1")).isTrue();
    assertThat(matcher.matches("CC2025R1&R2", "CC2025R2")).isTrue();
  }

  @Test
  void rejectsDifferentOrUnparseableProductVersions() {
    assertThat(matcher.matches("CC2026R3", "CC2026R4")).isFalse();
    assertThat(matcher.matches("日常评审", "CC2026R4")).isFalse();
  }

  @Test
  void keepsFollowingReleaseShorthandWithinItsAdjacentYearSegment() {
    String projectName = "CC2025R1&R2, CC2026R4&R5";

    assertThat(matcher.matches(projectName, "CC2025R1")).isTrue();
    assertThat(matcher.matches(projectName, "CC2025R2")).isTrue();
    assertThat(matcher.matches(projectName, "CC2026R4")).isTrue();
    assertThat(matcher.matches(projectName, "CC2026R5")).isTrue();
    assertThat(matcher.matches(projectName, "CC2025R5")).isFalse();
    assertThat(matcher.matches(projectName, "CC2026R2")).isFalse();
  }
}
