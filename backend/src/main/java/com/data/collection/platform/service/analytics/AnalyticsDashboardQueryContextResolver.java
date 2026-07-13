package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsDashboardQueryContextResolver {
  private static final String PAGE = "page";
  private static final String SIZE = "size";
  private static final String SORT_FIELD = "sortField";
  private static final String SORT_ORDER = "sortOrder";
  private static final Set<String> PAGINATION_CONTROL_KEYS = Set.of(PAGE, SIZE);
  private static final Set<String> SORT_CONTROL_KEYS = Set.of(SORT_FIELD, SORT_ORDER);

  private final CodeReviewMatchModeConfigService matchModeConfigService;

  public AnalyticsDashboardQueryContextResolver(
      CodeReviewMatchModeConfigService matchModeConfigService) {
    this.matchModeConfigService = matchModeConfigService;
  }

  public AnalyticsDashboardQueryContext resolveDashboard(
      AnalyticsDashboardProvider provider, Map<String, String> requestParameters) {
    return resolve(provider, requestParameters, provider.dashboardParameterKeys(), false, null);
  }

  public AnalyticsDashboardQueryContext resolveDetails(
      AnalyticsDashboardProvider provider,
      String viewKey,
      Map<String, String> requestParameters) {
    if (!provider.detailViewKeys().contains(viewKey)) {
      throw new BizException("分析看板不支持该详情: " + provider.dashboardKey() + "/" + viewKey);
    }
    return resolve(
        provider,
        requestParameters,
        provider.detailParameterKeys(viewKey),
        true,
        provider.detailSortableKeys(viewKey));
  }

  public AnalyticsDashboardQueryContext resolveExport(
      AnalyticsDashboardProvider provider,
      String exportKey,
      Map<String, String> requestParameters) {
    if (!provider.exportKeys().contains(exportKey)) {
      throw new BizException("分析看板不支持该导出: " + provider.dashboardKey() + "/" + exportKey);
    }
    var associatedDetailViewKey = provider.exportDetailViewKey(exportKey);
    Set<String> businessParameterKeys = associatedDetailViewKey
        .map(detailViewKey -> inheritedDetailParameterKeys(provider, detailViewKey, exportKey))
        .orElseGet(() -> provider.exportParameterKeys(exportKey));
    Set<String> sortableKeys = associatedDetailViewKey
        .map(provider::detailSortableKeys)
        .orElseGet(() -> provider.exportSortableKeys(exportKey));
    return resolve(
        provider,
        withoutPaginationControls(requestParameters),
        businessParameterKeys,
        false,
        sortableKeys);
  }

  private Set<String> inheritedDetailParameterKeys(
      AnalyticsDashboardProvider provider, String detailViewKey, String exportKey) {
    if (!provider.detailViewKeys().contains(detailViewKey)) {
      throw new BizException(
          "分析看板导出关联了未知详情: " + provider.dashboardKey() + "/" + exportKey + "/" + detailViewKey);
    }
    return provider.detailParameterKeys(detailViewKey);
  }

  private Map<String, String> withoutPaginationControls(Map<String, String> requestParameters) {
    Map<String, String> exportParameters = new LinkedHashMap<>(
        requestParameters == null ? Map.of() : requestParameters);
    PAGINATION_CONTROL_KEYS.forEach(exportParameters::remove);
    return exportParameters;
  }

  private AnalyticsDashboardQueryContext resolve(
      AnalyticsDashboardProvider provider,
      Map<String, String> requestParameters,
      Set<String> allowedParameterKeys,
      boolean paginationAllowed,
      Set<String> allowedSortableKeys) {
    boolean sortingAllowed = allowedSortableKeys != null;
    Map<String, String> parameters = new LinkedHashMap<>(
        requestParameters == null ? Map.of() : requestParameters);
    rejectUnknownParameters(
        parameters.keySet(), allowedParameterKeys, paginationAllowed, sortingAllowed);
    int page = paginationAllowed
        ? parseInteger(parameters.remove(PAGE), AnalyticsDashboardQueryContext.DEFAULT_PAGE, PAGE)
        : AnalyticsDashboardQueryContext.DEFAULT_PAGE;
    int size = paginationAllowed
        ? parseInteger(parameters.remove(SIZE), AnalyticsDashboardQueryContext.DEFAULT_SIZE, SIZE)
        : AnalyticsDashboardQueryContext.DEFAULT_SIZE;
    String sortField = sortingAllowed
        ? normalizeSortField(parameters.remove(SORT_FIELD), allowedSortableKeys)
        : null;
    String sortOrder = sortingAllowed ? normalizeSortOrder(parameters.remove(SORT_ORDER), sortField) : null;
    return new AnalyticsDashboardQueryContext(
        parameters,
        page,
        size,
        sortField,
        sortOrder,
        resolveReadMode(provider.readModeSource()));
  }

  private void rejectUnknownParameters(
      Set<String> requestedKeys,
      Set<String> allowedParameterKeys,
      boolean paginationAllowed,
      boolean sortingAllowed) {
    for (String key : requestedKeys) {
      if (!allowedParameterKeys.contains(key)
          && !(paginationAllowed && PAGINATION_CONTROL_KEYS.contains(key))
          && !(sortingAllowed && SORT_CONTROL_KEYS.contains(key))) {
        throw new BizException("不支持的看板参数: " + key);
      }
    }
  }

  private AnalyticsDashboardQueryContext.ReadMode resolveReadMode(
      AnalyticsDashboardProvider.ReadModeSource source) {
    if (source != AnalyticsDashboardProvider.ReadModeSource.CODE_REVIEW_SETTING) {
      return AnalyticsDashboardQueryContext.ReadMode.FORMAL;
    }
    //兼容模式-MatchMode：读模式只允许由服务端系统设置决定，客户端参数不得参与。
    return matchModeConfigService.isCodeReviewCompatibilityReadEnabled()
        ? AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE
        : AnalyticsDashboardQueryContext.ReadMode.FORMAL;
  }

  private int parseInteger(String value, int defaultValue, String parameterName) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      throw new BizException("参数 " + parameterName + " 必须是整数");
    }
  }

  private String normalizeSortOrder(String sortOrder, String sortField) {
    if (sortOrder == null || sortOrder.isBlank()) {
      return null;
    }
    if (sortField == null || sortField.isBlank()) {
      throw new BizException("使用排序方向时必须同时提供排序字段");
    }
    String normalized = sortOrder.trim().toLowerCase(java.util.Locale.ROOT);
    if (!"asc".equals(normalized) && !"desc".equals(normalized)) {
      throw new BizException("排序方向只支持 asc 或 desc");
    }
    return normalized;
  }

  private String normalizeSortField(String sortField, Set<String> allowedSortableKeys) {
    if (sortField == null || sortField.isBlank()) {
      return null;
    }
    String normalized = sortField.trim();
    if (!allowedSortableKeys.contains(normalized)) {
      throw new BizException("不支持的详情排序字段: " + normalized);
    }
    return normalized;
  }
}
