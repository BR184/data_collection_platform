package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AnalyticsDashboardRegistry {
  private final Map<String, AnalyticsDashboardProvider> providers;
  private final com.data.collection.platform.entity.read.DatasetReadSelection selection;

  @org.springframework.beans.factory.annotation.Autowired
  public AnalyticsDashboardRegistry(List<AnalyticsDashboardProvider> providerList) {
    Map<String, AnalyticsDashboardProvider> registered = new LinkedHashMap<>();
    for (AnalyticsDashboardProvider provider : providerList) {
      String key = provider.dashboardKey();
      if (!StringUtils.hasText(key)) {
        throw new BizException("看板 Provider 的 dashboardKey 不能为空");
      }
      if (registered.putIfAbsent(key, provider) != null) {
        throw new BizException("看板 Provider 键重复: " + key);
      }
    }
    providers = Map.copyOf(registered);
    selection = null;
  }

  private AnalyticsDashboardRegistry(Map<String, AnalyticsDashboardProvider> providers,
      com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    this.providers = providers;
    this.selection = java.util.Objects.requireNonNull(selection);
  }

  public AnalyticsDashboardProvider getRequired(String dashboardKey) {
    AnalyticsDashboardProvider provider = providers.get(dashboardKey);
    if (provider == null) {
      throw new BizException("分析看板不存在: " + dashboardKey);
    }
    return selection == null ? provider : provider.forRead(selection);
  }

  /** 创建固定身份注册表；只在访问目标看板时绑定其 Provider，不能提前绑定其它看板的来源闭包。 */
  public AnalyticsDashboardRegistry forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new AnalyticsDashboardRegistry(providers, selection);
  }

  public AnalyticsDashboardResponse loadDashboard(
      String dashboardKey, AnalyticsDashboardQueryContext context) {
    return getRequired(dashboardKey).loadDashboard(context);
  }

  public AnalyticsDashboardCacheIdentity cacheIdentity(
      String dashboardKey, AnalyticsDashboardQueryContext context) {
    AnalyticsDashboardProvider provider = getRequired(dashboardKey);
    String ruleVersion = requireVersion(provider.ruleVersion(context), "规则", dashboardKey);
    String sourceVersion = requireVersion(provider.sourceVersion(context), "数据源", dashboardKey);
    return new AnalyticsDashboardCacheIdentity(
        dashboardKey,
        ruleVersion,
        sourceVersion,
        context.scopeCacheKey(dashboardKey));
  }

  public AnalyticsDashboardRulesResponse loadRules(
      String dashboardKey, AnalyticsDashboardQueryContext context) {
    return getRequired(dashboardKey).loadRules(context);
  }

  public AnalyticsDashboardDetailResponse loadDetail(
      String dashboardKey, String viewKey, AnalyticsDashboardQueryContext context) {
    AnalyticsDashboardProvider provider = getRequired(dashboardKey);
    if (!provider.detailViewKeys().contains(viewKey)) {
      throw new BizException("分析看板不支持该详情: " + dashboardKey + "/" + viewKey);
    }
    return provider.loadDetail(viewKey, context);
  }

  public AnalyticsDashboardExport export(
      String dashboardKey, String exportKey, AnalyticsDashboardQueryContext context) {
    AnalyticsDashboardProvider provider = getRequired(dashboardKey);
    if (!provider.exportKeys().contains(exportKey)) {
      throw new BizException("分析看板不支持该导出: " + dashboardKey + "/" + exportKey);
    }
    return provider.export(exportKey, context);
  }

  private String requireVersion(String version, String versionType, String dashboardKey) {
    if (!StringUtils.hasText(version)) {
      throw new BizException("分析看板缺少" + versionType + "版本: " + dashboardKey);
    }
    return version.trim();
  }
}
