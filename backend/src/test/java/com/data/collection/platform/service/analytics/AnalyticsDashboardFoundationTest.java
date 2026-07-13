package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.controller.AnalyticsDashboardController;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AnalyticsDashboardFoundationTest {
  @Test
  void registryRoutesEveryCapabilityToTheRegisteredProvider() {
    var provider = new TestProvider("quality-rd", "quality-rules@1", "facts@10");
    var registry = new AnalyticsDashboardRegistry(List.of(provider));
    var context = AnalyticsDashboardQueryContext.of(Map.of("projectName", "CC2026R3"));

    assertThat(registry.loadDashboard("quality-rd", context).dashboardKey()).isEqualTo("quality-rd");
    assertThat(registry.loadRules("quality-rd", context).rules()).hasSize(1);
    assertThat(registry.loadDetail("quality-rd", "assignee-defects", context).records()).hasSize(1);
    assertThat(registry.export("quality-rd", "assignee-defects", context).filename())
        .isEqualTo("指派人剩余缺陷.xlsx");
  }

  @Test
  void registryRejectsDuplicateAndUnknownCapabilityKeys() {
    assertThatThrownBy(
            () -> new AnalyticsDashboardRegistry(
                List.of(
                    new TestProvider("quality-rd", "rules@1", "facts@1"),
                    new TestProvider("quality-rd", "rules@1", "facts@1"))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("重复");

    var registry = new AnalyticsDashboardRegistry(
        List.of(new TestProvider("quality-rd", "rules@1", "facts@1")));
    var context = AnalyticsDashboardQueryContext.empty();
    assertThatThrownBy(() -> registry.getRequired("missing"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> registry.loadDetail("quality-rd", "missing", context))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持该详情");
    assertThatThrownBy(() -> registry.export("quality-rd", "missing", context))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持该导出");
  }

  @Test
  void queryResolverUsesServerSettingAndRejectsClientControlledOrUnknownParameters() {
    var configService = mock(CodeReviewMatchModeConfigService.class);
    when(configService.isCodeReviewCompatibilityReadEnabled()).thenReturn(true);
    var resolver = new AnalyticsDashboardQueryContextResolver(configService);
    var formalProvider = new TestProvider("system-test-multi", "rules@1", "facts@1");
    var matchProvider = new TestProvider("quality-rd", "rules@1", "facts@1", true);
    var formal = resolver.resolveDetails(
        formalProvider,
        "assignee-defects",
        Map.of("projectName", " CC2026R3 ", "page", "2", "size", "50"));
    var matchMode = resolver.resolveDetails(
        matchProvider,
        "assignee-defects",
        Map.of("projectName", "CC2026R3", "page", "2", "size", "50"));

    assertThat(formal.parameter("projectName")).contains("CC2026R3");
    assertThat(formal.page()).isEqualTo(2);
    assertThat(formal.size()).isEqualTo(50);
    assertThat(formal.parameters()).doesNotContainKeys("page", "size");
    assertThat(formal.readMode()).isEqualTo(AnalyticsDashboardQueryContext.ReadMode.FORMAL);
    assertThat(matchMode.readMode()).isEqualTo(AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE);
    assertThat(formal.scopeCacheKey("quality-rd"))
        .isNotEqualTo(matchMode.scopeCacheKey("quality-rd"));
    assertThatThrownBy(() -> resolver.resolveDashboard(matchProvider, Map.of("readMode", "formal")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持的看板参数");
    assertThatThrownBy(() -> resolver.resolveDetails(
            matchProvider, "assignee-defects", Map.of("unknown", "value")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown");
  }

  @Test
  void cacheIdentityIncludesRuleAndSourceVersions() {
    var context = AnalyticsDashboardQueryContext.of(Map.of("projectName", "CC2026R3"));
    var firstRegistry = new AnalyticsDashboardRegistry(
        List.of(new TestProvider("quality-rd", "rules@1", "facts@1")));
    var changedRuleRegistry = new AnalyticsDashboardRegistry(
        List.of(new TestProvider("quality-rd", "rules@2", "facts@1")));
    var changedSourceRegistry = new AnalyticsDashboardRegistry(
        List.of(new TestProvider("quality-rd", "rules@1", "facts@2")));

    var first = firstRegistry.cacheIdentity("quality-rd", context);
    assertThat(first.identityKey())
        .isNotEqualTo(changedRuleRegistry.cacheIdentity("quality-rd", context).identityKey())
        .isNotEqualTo(changedSourceRegistry.cacheIdentity("quality-rd", context).identityKey());
    assertThat(first.ruleVersion()).isEqualTo("rules@1");
    assertThat(first.sourceVersion()).isEqualTo("facts@1");
  }

  @Test
  void exportInheritsDetailFiltersAndSortingButNotPagination() {
    var configService = mock(CodeReviewMatchModeConfigService.class);
    var resolver = new AnalyticsDashboardQueryContextResolver(configService);
    var provider = new TestProvider("quality-rd", "rules@1", "facts@1");

    var context = resolver.resolveExport(
        provider,
        "assignee-defects",
        Map.of(
            "projectName", "CC2026R3",
            "assigneeName", "张三",
            "page", "3",
            "size", "50",
            "sortField", "remainingCount",
            "sortOrder", "desc"));

    assertThat(context.parameters())
        .containsEntry("projectName", "CC2026R3")
        .containsEntry("assigneeName", "张三")
        .doesNotContainKeys("page", "size", "sortField", "sortOrder");
    assertThat(context.page()).isEqualTo(1);
    assertThat(context.size()).isEqualTo(20);
    assertThat(context.sortField()).isEqualTo("remainingCount");
    assertThat(context.sortOrder()).isEqualTo("desc");
    assertThatThrownBy(() -> resolver.resolveExport(
            provider,
            "assignee-defects",
            Map.of("sortField", "unknownField", "sortOrder", "asc")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持的详情排序字段");
  }

  @Test
  void controllerUsesRegistryForJsonAndWorkbookEndpoints() {
    var configService = mock(CodeReviewMatchModeConfigService.class);
    var controller = new AnalyticsDashboardController(
        new AnalyticsDashboardRegistry(
            List.of(new TestProvider("quality-rd", "rules@1", "facts@1"))),
        new AnalyticsDashboardQueryContextResolver(configService));

    assertThat(controller.getDashboard("quality-rd", Map.of()).getData().dashboardKey())
        .isEqualTo("quality-rd");
    assertThat(controller.getRules("quality-rd", Map.of()).getData().rules()).hasSize(1);
    assertThat(controller.getDetails("quality-rd", "assignee-defects", Map.of()).getData().viewKey())
        .isEqualTo("assignee-defects");

    var export = controller.export("quality-rd", "assignee-defects", Map.of());
    assertThat(export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("filename*=UTF-8''");
    assertThat(export.getBody()).containsExactly(1, 2, 3);
  }

  private static final class TestProvider implements AnalyticsDashboardProvider {
    private final String dashboardKey;
    private final String ruleVersion;
    private final String sourceVersion;
    private final boolean codeReviewReadMode;

    private TestProvider(String dashboardKey, String ruleVersion, String sourceVersion) {
      this(dashboardKey, ruleVersion, sourceVersion, false);
    }

    private TestProvider(
        String dashboardKey, String ruleVersion, String sourceVersion, boolean codeReviewReadMode) {
      this.dashboardKey = dashboardKey;
      this.ruleVersion = ruleVersion;
      this.sourceVersion = sourceVersion;
      this.codeReviewReadMode = codeReviewReadMode;
    }

    @Override
    public String dashboardKey() {
      return dashboardKey;
    }

    @Override
    public String ruleVersion(AnalyticsDashboardQueryContext context) {
      return ruleVersion;
    }

    @Override
    public String sourceVersion(AnalyticsDashboardQueryContext context) {
      return sourceVersion;
    }

    @Override
    public ReadModeSource readModeSource() {
      return codeReviewReadMode ? ReadModeSource.CODE_REVIEW_SETTING : ReadModeSource.FORMAL;
    }

    @Override
    public Set<String> dashboardParameterKeys() {
      return Set.of("projectName");
    }

    @Override
    public Set<String> detailParameterKeys(String viewKey) {
      return Set.of("projectName", "assigneeName");
    }

    @Override
    public Set<String> detailSortableKeys(String viewKey) {
      return Set.of("remainingCount");
    }

    @Override
    public AnalyticsDashboardResponse loadDashboard(AnalyticsDashboardQueryContext context) {
      var metric = new AnalyticsDashboardResponse.Metric(
          "defect-density",
          "缺陷密度",
          BigDecimal.ONE,
          "1.00",
          "%",
          "quality.defect-density",
          null,
          null);
      return new AnalyticsDashboardResponse(dashboardKey, "研发质量看板", null, List.of(metric), List.of());
    }

    @Override
    public AnalyticsDashboardRulesResponse loadRules(AnalyticsDashboardQueryContext context) {
      return new AnalyticsDashboardRulesResponse(
          dashboardKey,
          List.of(new AnalyticsDashboardRulesResponse.Rule(
              "quality.defect-density", "缺陷密度", "缺陷数 / 规模", "当前项目", null, null)));
    }

    @Override
    public Set<String> detailViewKeys() {
      return Set.of("assignee-defects");
    }

    @Override
    public AnalyticsDashboardDetailResponse loadDetail(
        String viewKey, AnalyticsDashboardQueryContext context) {
      return new AnalyticsDashboardDetailResponse(
          dashboardKey,
          viewKey,
          "指派人剩余缺陷",
          null,
          List.of(new AnalyticsDashboardDetailResponse.Column("assignee", "指派人", "text", 160)),
          List.of(Map.of("assignee", "张三")),
          1,
          context.page(),
          context.size(),
          List.of(new AnalyticsDashboardResponse.ExportAction("assignee-defects", "导出汇总")));
    }

    @Override
    public Set<String> exportKeys() {
      return Set.of("assignee-defects");
    }

    @Override
    public Optional<String> exportDetailViewKey(String exportKey) {
      return Optional.of("assignee-defects");
    }

    @Override
    public AnalyticsDashboardExport export(
        String exportKey, AnalyticsDashboardQueryContext context) {
      return AnalyticsDashboardExport.xlsx("指派人剩余缺陷.xlsx", new byte[] {1, 2, 3});
    }
  }
}
