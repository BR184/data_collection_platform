package com.data.collection.platform.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardVersionScopeServiceTest {
  private static final long PROJECT_ID = IssueScopeCatalogService.CROWN_CAD_PROJECT_ID;
  private static final DashboardVersionScopeConfig.ScopeDefinition SCOPE =
      DashboardVersionScopeTestSupport.scope(
          "cc-2027-r1-r2",
          "CC2027R1&R2",
          "CC2027R1",
          List.of("CC2027R1", "CC2027R2"),
          List.of("2027R1&R2"),
          List.of());

  @Test
  void candidatesReplaceMembersAtTheEarliestMemberPositionAndKeepOtherOrder() {
    DashboardVersionScopeService service = service(
        DashboardVersionScopeTestSupport.group(
            11L,
            "CC2026R3",
            1,
            DashboardVersionScopeTestSupport.member(111L, "CC2026R3第一轮系统测试", 1)),
        r1Group(),
        r2Group(),
        DashboardVersionScopeTestSupport.group(
            12L,
            "CC2026R2",
            4,
            DashboardVersionScopeTestSupport.member(121L, "CC2026R2回归测试", 1)));

    var options = service.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE);

    assertThat(options).extracting(DashboardVersionScopeService.VersionOption::value)
        .containsExactly("CC2026R3", "cc-2027-r1-r2", "CC2026R2");
    assertThat(options).extracting(DashboardVersionScopeService.VersionOption::displayName)
        .containsExactly("CC2026R3", "CC2027R1&R2", "CC2026R2");
    assertThat(options.get(1).groupId()).isEqualTo(10L);
    assertThat(options.get(1).businessKey()).isEqualTo("cc-2027-r1-r2");
    assertThat(options.getFirst().value()).isEqualTo("CC2026R3");
  }

  @Test
  void combinedScopeUnionsEnabledMembersInCatalogOrderAndDeduplicatesPhases() {
    DashboardVersionScopeService service = service(r1Group(), r2Group());

    DashboardVersionScope scope = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2");

    assertThat(scope.combined()).isTrue();
    assertThat(scope.businessKey()).isEqualTo("cc-2027-r1-r2");
    assertThat(scope.anchorGroupId()).isEqualTo(10L);
    assertThat(scope.memberBusinessKeys()).containsExactly("CC2027R1", "CC2027R2");
    assertThat(scope.phases()).extracting(DashboardVersionScope.PhaseMember::id)
        .containsExactly(101L, 102L, 201L, 202L, 203L);
    assertThat(scope.reviewProjectNames())
        .containsExactly("CC2027R1", "CC2027R2", "2027R1&R2");
    assertThat(scope.integrationTestPhases())
        .containsExactly("CC2027R1集成测试", "CC2027R2集成测试");
    assertThat(scope.fingerprint()).isNotBlank();
  }

  @Test
  void membersReplacedByTheCombinationCannotBeQueriedSeparately() {
    DashboardVersionScopeService service = service(r1Group(), r2Group());

    assertThatThrownBy(() -> service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已合并为 CC2027R1&R2");
    assertThatThrownBy(() -> service.resolveByGroupId(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, 20L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已合并为 CC2027R1&R2");
  }

  @Test
  void independentVersionsRemainSingleMemberScopes() {
    DashboardVersionScopeService service = service(
        r1Group(),
        r2Group(),
        DashboardVersionScopeTestSupport.group(
            11L,
            "CC2026R3",
            5,
            DashboardVersionScopeTestSupport.member(111L, "CC2026R3第一轮系统测试", 1)));

    DashboardVersionScope scope = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2");
    assertThat(scope.combined()).isTrue();
    assertThat(service.resolveByGroupId(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, 10L).combined()).isTrue();

    DashboardVersionScope other = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2026R3");
    assertThat(other.combined()).isFalse();
    assertThat(other.memberBusinessKeys()).containsExactly("CC2026R3");
    assertThat(other.phases()).extracting(DashboardVersionScope.PhaseMember::sourceValue)
        .containsExactly("CC2026R3第一轮系统测试");
  }

  @Test
  void missingSecondMemberKeepsTheAnchorScopeAvailable() {
    DashboardVersionScopeService service = service(r1Group());

    var options = service.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE);
    assertThat(options).extracting(DashboardVersionScopeService.VersionOption::value)
        .containsExactly("cc-2027-r1-r2");

    DashboardVersionScope scope = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2");
    assertThat(scope.memberBusinessKeys()).containsExactly("CC2027R1");
    assertThat(scope.phases()).extracting(DashboardVersionScope.PhaseMember::sourceValue)
        .containsExactly("CC2027R1第一轮系统测试", "CC2027R1回归测试");
  }

  @Test
  void missingAnchorKeepsTheOriginalMembersAccessible() {
    DashboardVersionScopeService service = service(r2Group());

    var options = service.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE);
    assertThat(options).extracting(DashboardVersionScopeService.VersionOption::value)
        .containsExactly("CC2027R2");
    assertThat(service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R2").combined()).isFalse();
    assertThatThrownBy(() -> service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void ambiguousPhaseValueAcrossMembersIsReportedAsConfigurationConflict() {
    DashboardVersionScopeService service = service(
        DashboardVersionScopeTestSupport.group(
            10L,
            "CC2027R1",
            1,
            DashboardVersionScopeTestSupport.member(101L, "共享系统测试", 1)),
        DashboardVersionScopeTestSupport.group(
            20L,
            "CC2027R2",
            2,
            DashboardVersionScopeTestSupport.member(201L, "共享系统测试", 1)));

    assertThatThrownBy(() -> service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("配置冲突")
        .hasMessageContaining("共享系统测试");
  }

  @Test
  void registeredCombinedSourcesAreUsedWithoutInventingPhaseNames() {
    DashboardVersionScopeConfig.ScopeDefinition explicit =
        DashboardVersionScopeTestSupport.scope(
            "cc-2027-r1-r2",
            "CC2027R1&R2",
            "CC2027R1",
            List.of("CC2027R1", "CC2027R2"),
            List.of("2027R1&R2", "2027R1&R2回归"),
            List.of("2027R1&R2集成测试"));
    DashboardVersionScopeService service = service(explicit, r1Group(), r2Group());

    DashboardVersionScope scope = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2027R1&R2");

    assertThat(scope.reviewProjectNames())
        .containsExactly("CC2027R1", "CC2027R2", "2027R1&R2", "2027R1&R2回归");
    assertThat(scope.integrationTestPhases()).containsExactly("2027R1&R2集成测试");
  }

  @Test
  void renamedNormalVersionKeepsStableKeyForDefaultAndSelection() {
    DashboardVersionScopeService service = service(
        DashboardVersionScopeTestSupport.group(
            11L,
            "CC2026R3",
            "CC 2026 R3 发布版",
            1,
            DashboardVersionScopeTestSupport.member(111L, "CC2026R3第一轮系统测试", 1)));

    var options = service.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE);
    assertThat(options).singleElement().satisfies(option -> {
      assertThat(option.value()).isEqualTo("CC2026R3");
      assertThat(option.displayName()).isEqualTo("CC 2026 R3 发布版");
    });
    assertThat(service.resolve(PROJECT_ID, IssueScopeDimension.TESTING_PHASE, null).businessKey())
        .isEqualTo("CC2026R3");
    assertThat(service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2026R3").displayName())
        .isEqualTo("CC 2026 R3 发布版");
  }

  @Test
  void renamedCombinedVersionKeepsStableKeyAndMembers() {
    DashboardVersionScopeConfig.ScopeDefinition renamed =
        DashboardVersionScopeTestSupport.scope(
            "cc-2027-r1-r2",
            "2027 R1与R2合并",
            "CC2027R1",
            List.of("CC2027R1", "CC2027R2"),
            List.of(),
            List.of());
    DashboardVersionScopeService service = service(renamed, r1Group(), r2Group());

    var option = service.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE).getFirst();
    assertThat(option.value()).isEqualTo("cc-2027-r1-r2");
    assertThat(option.displayName()).isEqualTo("2027 R1与R2合并");

    DashboardVersionScope scope = service.resolve(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "cc-2027-r1-r2");
    assertThat(scope.businessKey()).isEqualTo("cc-2027-r1-r2");
    assertThat(scope.displayName()).isEqualTo("2027 R1与R2合并");
    assertThat(scope.memberBusinessKeys()).containsExactly("CC2027R1", "CC2027R2");
  }

  @Test
  void unknownVersionIsRejectedInsteadOfSilentlyReturningEmptyScope() {
    DashboardVersionScopeService service = service(r1Group(), r2Group());

    assertThatThrownBy(() -> service.resolve(
            PROJECT_ID, IssueScopeDimension.TESTING_PHASE, "CC2099R9"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不存在或已停用");
  }

  private DashboardVersionScopeService service(ScopeGroup... groups) {
    return DashboardVersionScopeTestSupport.service(List.of(groups), List.of(SCOPE));
  }

  private DashboardVersionScopeService service(
      DashboardVersionScopeConfig.ScopeDefinition definition, ScopeGroup... groups) {
    return DashboardVersionScopeTestSupport.service(List.of(groups), List.of(definition));
  }

  private ScopeGroup r1Group() {
    return DashboardVersionScopeTestSupport.group(
        10L,
        "CC2027R1",
        2,
        DashboardVersionScopeTestSupport.member(101L, "CC2027R1第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(102L, "CC2027R1回归测试", 2));
  }

  private ScopeGroup r2Group() {
    return DashboardVersionScopeTestSupport.group(
        20L,
        "CC2027R2",
        3,
        DashboardVersionScopeTestSupport.member(201L, "CC2027R2第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(202L, "CC2027R2第二轮系统测试", 2),
        DashboardVersionScopeTestSupport.member(203L, "CC2027R2回归测试", 3));
  }
}
