package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.dashboard.DashboardVersionScope;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeService;
import java.util.List;

/** 将平台议题测试阶段目录适配为 BI 的稳定产品版本端口。 */
public final class BiPlatformProductVersionAdapter implements BiProductVersionPort {
  private static final long PROJECT_ID = IssueScopeCatalogService.CROWN_CAD_PROJECT_ID;

  private final DashboardVersionScopeService versionScopeService;

  public BiPlatformProductVersionAdapter(DashboardVersionScopeService versionScopeService) {
    this.versionScopeService = versionScopeService;
  }

  @Override
  public BiProductVersionCatalog catalog() {
    List<DashboardVersionScopeService.VersionOption> options =
        versionScopeService.listOptions(PROJECT_ID, IssueScopeDimension.TESTING_PHASE);
    Long defaultId = options.isEmpty() ? null : options.getFirst().groupId();
    List<BiProductVersionOption> versions = options.stream()
        .map(option -> new BiProductVersionOption(
            option.groupId(),
            option.businessKey(),
            option.displayName(),
            option.sortOrder()))
        .toList();
    return new BiProductVersionCatalog(defaultId, versions);
  }

  @Override
  public BiProductVersionScope requireScope(long productVersionId) {
    DashboardVersionScope scope = versionScopeService.resolveByGroupId(
        PROJECT_ID, IssueScopeDimension.TESTING_PHASE, productVersionId);
    List<BiProductVersionScope.RoundScope> rounds = scope.phases().stream()
        .map(phase -> new BiProductVersionScope.RoundScope(
            Long.toString(phase.id()), phase.sourceValue(), phase.displayName(), phase.sortOrder()))
        .toList();
    return new BiProductVersionScope(
        scope.anchorGroupId(),
        scope.projectId(),
        scope.businessKey(),
        scope.displayName(),
        scope.sortOrder(),
        rounds,
        scope.memberBusinessKeys(),
        scope.fingerprint());
  }
}
