package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardExport;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import java.util.Set;
import java.util.Optional;

public interface AnalyticsDashboardProvider {
  String dashboardKey();

  String ruleVersion(AnalyticsDashboardQueryContext context);

  String sourceVersion(AnalyticsDashboardQueryContext context);

  default ReadModeSource readModeSource() {
    return ReadModeSource.FORMAL;
  }

  default Set<String> dashboardParameterKeys() {
    return Set.of();
  }

  AnalyticsDashboardResponse loadDashboard(AnalyticsDashboardQueryContext context);

  AnalyticsDashboardRulesResponse loadRules(AnalyticsDashboardQueryContext context);

  default Set<String> detailViewKeys() {
    return Set.of();
  }

  default Set<String> detailParameterKeys(String viewKey) {
    return dashboardParameterKeys();
  }

  default Set<String> detailSortableKeys(String viewKey) {
    return Set.of();
  }

  default AnalyticsDashboardDetailResponse loadDetail(
      String viewKey, AnalyticsDashboardQueryContext context) {
    throw new BizException("看板不支持该详情: " + viewKey);
  }

  default Set<String> exportKeys() {
    return Set.of();
  }

  default Set<String> exportParameterKeys(String exportKey) {
    return Set.of();
  }

  /** Export may explicitly inherit the exact business filter contract of one detail view. */
  default Optional<String> exportDetailViewKey(String exportKey) {
    return Optional.empty();
  }

  default Set<String> exportSortableKeys(String exportKey) {
    return Set.of();
  }

  default AnalyticsDashboardExport export(
      String exportKey, AnalyticsDashboardQueryContext context) {
    throw new BizException("看板不支持该导出: " + exportKey);
  }

  enum ReadModeSource {
    FORMAL,
    CODE_REVIEW_SETTING
  }
}
