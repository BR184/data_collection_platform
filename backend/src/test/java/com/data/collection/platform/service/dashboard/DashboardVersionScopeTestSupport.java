package com.data.collection.platform.service.dashboard;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeCatalogService.ScopeGroup;
import com.data.collection.platform.service.IssueScopeCatalogService.ScopeMember;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.Arrays;
import java.util.List;

/** 不连数据库的看板版本范围测试装配。 */
public final class DashboardVersionScopeTestSupport {
  private DashboardVersionScopeTestSupport() {}

  public static DashboardVersionScopeService service(ScopeGroup... groups) {
    return service(Arrays.asList(groups), List.of());
  }

  public static DashboardVersionScopeService service(
      List<ScopeGroup> groups, List<DashboardVersionScopeConfig.ScopeDefinition> scopes) {
    IssueScopeCatalogService catalogService = mock(IssueScopeCatalogService.class);
    when(catalogService.listEnabledGroups(
            IssueScopeCatalogService.CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE))
        .thenReturn(groups);
    DashboardVersionScopeConfigLoader loader = mock(DashboardVersionScopeConfigLoader.class);
    when(loader.config())
        .thenReturn(new DashboardVersionScopeConfig(1, scopes));
    when(loader.resourceHash()).thenReturn("test-resource-hash");
    return new DashboardVersionScopeService(catalogService, loader);
  }

  public static ScopeGroup group(
      long id, String businessKey, int sortOrder, ScopeMember... members) {
    return group(id, businessKey, businessKey, sortOrder, members);
  }

  public static ScopeGroup group(
      long id,
      String businessKey,
      String displayName,
      int sortOrder,
      ScopeMember... members) {
    return new ScopeGroup(
        1L,
        IssueScopeCatalogService.CROWN_CAD_PROJECT_ID,
        "CrownCAD",
        IssueScopeDimension.TESTING_PHASE,
        id,
        businessKey,
        displayName,
        sortOrder,
        List.of(members));
  }

  public static ScopeMember member(long id, String sourceValue, int sortOrder) {
    return new ScopeMember(id, sourceValue, sourceValue, sortOrder, null, null);
  }

  public static DashboardVersionScopeConfig.ScopeDefinition scope(
      String key,
      String displayName,
      String anchorBusinessKey,
      List<String> memberBusinessKeys,
      List<String> reviewProjectNames,
      List<String> integrationTestPhases) {
    return new DashboardVersionScopeConfig.ScopeDefinition(
        key,
        displayName,
        IssueScopeCatalogService.CROWN_CAD_PROJECT_ID,
        IssueScopeDimension.TESTING_PHASE.name(),
        anchorBusinessKey,
        memberBusinessKeys,
        reviewProjectNames,
        integrationTestPhases);
  }
}
