package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.PageRecordSnapshotService;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeConfig;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeService;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiPlatformProductVersionAdapterTest {
  private static final DashboardVersionScopeConfig.ScopeDefinition SCOPE =
      DashboardVersionScopeTestSupport.scope(
          "cc-2027-r1-r2",
          "CC2027R1&R2",
          "CC2027R1",
          List.of("CC2027R1", "CC2027R2"),
          List.of("2027R1&R2"),
          List.of());

  private final DashboardVersionScopeService versionScopeService =
      DashboardVersionScopeTestSupport.service(
          List.of(
              DashboardVersionScopeTestSupport.group(
                  30L,
                  "CC2026R3",
                  1,
                  DashboardVersionScopeTestSupport.member(301L, "CC2026R3回归测试", 1)),
              r1Group(),
              r2Group()),
          List.of(SCOPE));
  private final BiPlatformProductVersionAdapter adapter =
      new BiPlatformProductVersionAdapter(versionScopeService);

  @Test
  void catalogExposesOneCombinedEntryAtTheAnchorPositionAndHidesMembers() {
    var catalog = adapter.catalog();

    assertThat(catalog.defaultVersionId()).isEqualTo(30L);
    assertThat(catalog.versions()).extracting(option -> option.displayName())
        .containsExactly("CC2026R3", "CC2027R1&R2");
    var combined = catalog.versions().get(1);
    assertThat(combined.id()).isEqualTo(10L);
    assertThat(combined.businessKey()).isEqualTo("cc-2027-r1-r2");
  }

  @Test
  void requireScopeUnionsMemberRoundsWithoutDuplicatingIssues() {
    BiProductVersionScope scope = adapter.requireScope(10L);

    assertThat(scope.businessKey()).isEqualTo("cc-2027-r1-r2");
    assertThat(scope.displayName()).isEqualTo("CC2027R1&R2");
    assertThat(scope.memberBusinessKeys()).containsExactly("CC2027R1", "CC2027R2");
    assertThat(scope.rounds())
        .extracting(BiProductVersionScope.RoundScope::sourceValue)
        .containsExactly(
            "CC2027R1第一轮系统测试", "CC2027R1回归测试",
            "CC2027R2第一轮系统测试", "CC2027R2第二轮系统测试", "CC2027R2回归测试");
    assertThat(scope.fingerprint()).isNotBlank();
  }

  @Test
  void hiddenMembersAndIndependentVersionsFollowTheCandidateRules() {
    assertThatThrownBy(() -> adapter.requireScope(20L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已合并为 CC2027R1&R2");

    BiProductVersionScope independent = adapter.requireScope(30L);
    assertThat(independent.memberBusinessKeys()).containsExactly("CC2026R3");
    assertThat(independent.rounds()).hasSize(1);
  }

  @Test
  void currentSourceVersionChangesWhenTheScopeFingerprintChanges() {
    PageRecordSnapshotService snapshotService = mock(PageRecordSnapshotService.class);
    when(snapshotService.issueFactSourceVersion(anyString(), anyLong(), any(), anyString()))
        .thenReturn("facts-v1");
    when(snapshotService.reviewDataSourceVersion()).thenReturn("review-v1");
    when(snapshotService.codeReviewSourceVersion()).thenReturn("coding-v1");
    CodeReviewMatchModeSwitchService switchService = mock(CodeReviewMatchModeSwitchService.class);
    when(switchService.isCodeReviewCompatibilityReadEnabled()).thenReturn(false);
    var contextFactory = new BiPlatformCodeReviewSourceContextFactory(
        switchService::isCodeReviewCompatibilityReadEnabled,
        snapshotService,
        () -> "compat-v1",
        () -> false,
        () -> "commits-v1");
    var currentVersions = new BiPlatformCurrentSourceVersionAdapter(
        snapshotService, contextFactory, mock(BiCatMirrorRepository.class));

    BiProductVersionScope first = scopeWithFingerprint("fingerprint-a");
    BiProductVersionScope second = scopeWithFingerprint("fingerprint-b");

    assertThat(currentVersions.current("system-test", first))
        .isEqualTo("facts-v1|facts-v1|scope:fingerprint-a");
    assertThat(currentVersions.current("system-test", second))
        .isEqualTo("facts-v1|facts-v1|scope:fingerprint-b");
    assertThat(currentVersions.current("requirements", first))
        .isEqualTo("review-v1|scope:fingerprint-a");
  }

  private static com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup r1Group() {
    return DashboardVersionScopeTestSupport.group(
        10L,
        "CC2027R1",
        2,
        DashboardVersionScopeTestSupport.member(101L, "CC2027R1第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(102L, "CC2027R1回归测试", 2));
  }

  private static com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup r2Group() {
    return DashboardVersionScopeTestSupport.group(
        20L,
        "CC2027R2",
        3,
        DashboardVersionScopeTestSupport.member(201L, "CC2027R2第一轮系统测试", 1),
        DashboardVersionScopeTestSupport.member(202L, "CC2027R2第二轮系统测试", 2),
        DashboardVersionScopeTestSupport.member(203L, "CC2027R2回归测试", 3));
  }

  private BiProductVersionScope scopeWithFingerprint(String fingerprint) {
    return new BiProductVersionScope(
        10L,
        9L,
        "cc-2027-r1-r2",
        "CC2027R1&R2",
        2,
        List.of(),
        List.of("CC2027R1", "CC2027R2"),
        fingerprint);
  }
}
