package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiCatProductVersionAdapterTest {
  private final IssueScopeCatalogService catalogService = mock(IssueScopeCatalogService.class);

  @Test
  void catalogAndScopeKeepOriginalDirectoryEntriesAndBusinessKeys() {
    var r1 = DashboardVersionScopeTestSupport.group(
        10L,
        "CC2027R1",
        2,
        DashboardVersionScopeTestSupport.member(101L, "CC2027R1第一轮系统测试", 1));
    var r2 = DashboardVersionScopeTestSupport.group(
        20L,
        "CC2027R2",
        3,
        DashboardVersionScopeTestSupport.member(201L, "CC2027R2第一轮系统测试", 1));
    when(catalogService.listEnabledGroups(
            IssueScopeCatalogService.CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE))
        .thenReturn(List.of(r1, r2));
    BiCatProductVersionAdapter adapter = new BiCatProductVersionAdapter(catalogService);

    var catalog = adapter.catalog();
    assertThat(catalog.defaultVersionId()).isEqualTo(10L);
    assertThat(catalog.versions()).extracting(option -> option.businessKey())
        .containsExactly("CC2027R1", "CC2027R2");
    assertThat(adapter.requireScope(20L).businessKey()).isEqualTo("CC2027R2");
    assertThat(adapter.requireScope(20L).memberBusinessKeys()).containsExactly("CC2027R2");

    var dashboards = DashboardVersionScopeTestSupport.service(
        List.of(r1, r2),
        List.of(DashboardVersionScopeTestSupport.scope(
            "cc-2027-r1-r2",
            "CC2027R1&R2",
            "CC2027R1",
            List.of("CC2027R1", "CC2027R2"),
            List.of(),
            List.of())));
    assertThat(dashboards.listOptions(
            IssueScopeCatalogService.CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE))
        .extracting(option -> option.value())
        .containsExactly("cc-2027-r1-r2");
  }
}
