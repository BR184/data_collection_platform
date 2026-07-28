package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemTestPhaseMembershipPolicyTest {

  @Test
  void exactMemberRejectsCompoundFactWhileContainsMemberAcceptsIt() {
    List<String> members = List.of("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试");
    String compound = "历史阶段 & CC2026R4第一轮系统测试";

    assertThat(SystemTestPhaseMembershipPolicy.matches(
            compound,
            members,
            SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER))
        .isFalse();
    assertThat(SystemTestPhaseMembershipPolicy.matches(
            compound,
            members,
            SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER))
        .isTrue();
  }

  @Test
  void filterMatchingUsesTheSameConfiguredMembersAndModeAsSql() {
    SystemTestPhaseScopeResolver resolver = resolver();
    StatisticFilterGroup filterGroup = phaseFilter("CC2026R4");
    TestIssue issue = new TestIssue("历史阶段 & CC2026R4第一轮系统测试");
    var containsMembership = SystemTestPhaseMembershipPolicy.membership(
        filterGroup,
        resolver,
        SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER);
    var exactMembership = SystemTestPhaseMembershipPolicy.membership(
        filterGroup,
        resolver,
        SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER);

    assertThat(containsMembership.matches(issue)).isTrue();
    assertThat(containsMembership.matches(issue)).isTrue();
    assertThat(exactMembership.matches(issue)).isFalse();
    verify(resolver, times(2)).resolvePhases(9L, "CC2026R4");
  }

  @Test
  void sqlPredicateReflectsTheSelectedMembershipMode() {
    SystemTestPhaseScopeResolver resolver = resolver();
    StatisticFilterGroup filterGroup = phaseFilter("CC2026R4");

    var containsPredicate = SystemTestPhaseMembershipPolicy.sqlPredicate(
        filterGroup,
        resolver,
        SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER);
    var exactPredicate = SystemTestPhaseMembershipPolicy.sqlPredicate(
        filterGroup,
        resolver,
        SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER);

    assertThat(containsPredicate.sql())
        .isEqualTo("testing_phase like ? or testing_phase like ?");
    assertThat(containsPredicate.args())
        .containsExactly("%CC2026R4第一轮系统测试%", "%CC2026R4第二轮系统测试%");
    assertThat(exactPredicate.sql())
        .isEqualTo("testing_phase = ? or testing_phase = ?");
    assertThat(exactPredicate.args())
        .containsExactly("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试");
  }

  @Test
  void selectedTestingPhaseAndSafeContainsPrefilterRemainCentralized() {
    StatisticFilterGroup filterGroup = phaseFilter("  CC2026R4  ");

    assertThat(SystemTestPhaseMembershipPolicy.selectedTestingPhase(filterGroup))
        .isEqualTo("CC2026R4");
    assertThat(SystemTestPhaseMembershipPolicy.containsPrefilterValue(
            "CC2026R4",
            List.of("CC2026R4第一轮系统测试", "CC2026R4回归测试")))
        .isEqualTo("CC2026R4");
    assertThat(SystemTestPhaseMembershipPolicy.containsPrefilterValue(
            "release-r4",
            List.of("CC2026R4第一轮系统测试")))
        .isNull();
    assertThat(SystemTestPhaseMembershipPolicy.containsPrefilterValue(
            "cc2026r4",
            List.of("CC2026R4第一轮系统测试")))
        .isNull();
  }

  private SystemTestPhaseScopeResolver resolver() {
    SystemTestPhaseScopeResolver resolver = mock(SystemTestPhaseScopeResolver.class);
    when(resolver.listEnabledParentNames(9L)).thenReturn(List.of("CC2026R4"));
    when(resolver.resolvePhases(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试"));
    return resolver;
  }

  private StatisticFilterGroup phaseFilter(String value) {
    return new StatisticFilterGroup(
        "AND", List.of(new StatisticFilterCondition("testingPhase", "eq", value, null)));
  }

  private record TestIssue(String phaseFilterValue) implements SystemTestPhaseFilterSource {}
}
