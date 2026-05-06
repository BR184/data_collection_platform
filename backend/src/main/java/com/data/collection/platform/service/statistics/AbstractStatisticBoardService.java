package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.Map;

public abstract class AbstractStatisticBoardService {
  // 统计板基类统一处理定义、筛选解析、明细查询和 CSV 导出。
  // 具体看板只实现领域数据聚合，避免每个看板重复解析高级筛选 JSON。
  private final JsonUtils jsonUtils;

  protected AbstractStatisticBoardService(JsonUtils jsonUtils) {
    this.jsonUtils = jsonUtils;
  }

  public abstract String boardKey();

  protected abstract StatisticBoardDefinition buildDefinition();

  public StatisticBoardDefinition getDefinition() {
    return buildDefinition();
  }

  public StatisticBoardResponse loadBoard(Map<String, String> filters) {
    Map<String, String> safeFilters = filters == null ? Map.of() : filters;
    StatisticBoardDefinition definition = buildDefinition();
    StatisticFilterGroup filterGroup = parseFilterGroup(safeFilters, definition);
    return doLoadBoard(safeFilters, filterGroup);
  }

  public StatisticDetailResponse loadDetail(StatisticDetailRequest request) {
    StatisticBoardDefinition definition = buildDefinition();
    StatisticFilterGroup filterGroup = parseFilterGroup(request.filters(), definition);
    return doLoadDetail(request, filterGroup);
  }

  public String exportBoardCsv(Map<String, String> filters) {
    return StatisticBoardCsvSupport.export(loadBoard(filters));
  }

  protected abstract StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup);

  protected abstract StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup);

  protected StatisticFilterGroup parseFilterGroup(Map<String, String> filters, StatisticBoardDefinition definition) {
    return StatisticFilterGroupSupport.parseFilterGroup(jsonUtils, filters, definition);
  }

  protected Map<String, String> withoutReservedFilters(Map<String, String> filters) {
    return StatisticFilterGroupSupport.withoutReservedFilters(filters);
  }

  protected StatisticFilterGroup emptyFilterGroup() {
    return StatisticFilterGroupSupport.emptyFilterGroup();
  }

  protected java.util.Set<String> filterableFieldKeys(StatisticBoardDefinition definition) {
    return StatisticFilterGroupSupport.filterableFieldKeys(definition);
  }

  protected String trimToNull(String value) {
    return StatisticFilterGroupSupport.trimToNull(value);
  }
}
