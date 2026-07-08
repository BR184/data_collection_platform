package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractStatisticBoardService implements StatisticBoardWorkbookExportSupport {
  protected static final String DETAIL_KEYWORD_FILTER = "detailKeyword";
  private static final String DETAIL_COLUMN_FILTER_PREFIX = "detailFilter.";

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

  @Override
  public byte[] exportBoardWorkbook(Map<String, String> filters) {
    return StatisticBoardWorkbookSupport.export(loadBoard(filters));
  }

  @Override
  public String exportFilename() {
    return buildDefinition().title() + ".xlsx";
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    return exportFilename();
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

  protected <T> DetailRecordPage sliceDetailRecords(
      StatisticDetailRequest request,
      List<T> sortedSources,
      Function<T, Map<String, Object>> detailRecordMapper) {
    List<Map<String, Object>> allRecords =
        (sortedSources == null ? List.<T>of() : sortedSources).stream()
            .map(detailRecordMapper)
            .toList();
    List<Map<String, Object>> filteredRecords =
        allRecords.stream()
            .filter(record -> matchesDetailQuickFilters(record, request.filters()))
            .toList();
    Map<String, List<String>> quickFilterOptions = buildDetailQuickFilterOptions(allRecords);
    PageSlice<Map<String, Object>> pageSlice =
        PageSliceSupport.slice(filteredRecords, request.page(), request.size() <= 0 ? 10 : request.size());
    return new DetailRecordPage(
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        quickFilterOptions);
  }

  private boolean matchesDetailQuickFilters(Map<String, Object> record, Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return true;
    }
    String keyword = normalizeForDetailMatch(filters.get(DETAIL_KEYWORD_FILTER));
    if (keyword != null && record.values().stream().noneMatch(value -> detailCellMatches(value, keyword))) {
      return false;
    }
    for (Map.Entry<String, String> entry : filters.entrySet()) {
      if (!entry.getKey().startsWith(DETAIL_COLUMN_FILTER_PREFIX)) {
        continue;
      }
      String columnKey = trimToNull(entry.getKey().substring(DETAIL_COLUMN_FILTER_PREFIX.length()));
      String expected = normalizeForDetailMatch(entry.getValue());
      if (columnKey == null || expected == null) {
        continue;
      }
      Object value = record.get(columnKey);
      if (!detailCellMatches(value, expected)) {
        return false;
      }
    }
    return true;
  }

  private boolean detailCellMatches(Object value, String expected) {
    if (value == null) {
      return false;
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (detailCellMatches(item, expected)) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("label")) {
        return detailCellMatches(map.get("label"), expected);
      }
      return map.values().stream().anyMatch(item -> detailCellMatches(item, expected));
    }
    String actual = normalizeForDetailMatch(String.valueOf(value));
    return actual != null && actual.contains(expected);
  }

  private String normalizeForDetailMatch(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  private Map<String, List<String>> buildDetailQuickFilterOptions(List<Map<String, Object>> records) {
    if (records == null || records.isEmpty()) {
      return Map.of();
    }
    return records.stream()
        .flatMap(record -> record.entrySet().stream())
        .collect(Collectors.groupingBy(
            Map.Entry::getKey,
            Collectors.flatMapping(
                entry -> detailOptionValues(entry.getValue()).stream(),
                Collectors.collectingAndThen(
                    Collectors.toCollection(java.util.TreeSet::new),
                    values -> values.stream().limit(200).toList()))));
  }

  private List<String> detailOptionValues(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof Iterable<?> iterable) {
      java.util.List<String> values = new java.util.ArrayList<>();
      for (Object item : iterable) {
        values.addAll(detailOptionValues(item));
      }
      return values;
    }
    if (value instanceof Map<?, ?> map) {
      if (map.containsKey("label")) {
        return detailOptionValues(map.get("label"));
      }
      return map.values().stream().flatMap(item -> detailOptionValues(item).stream()).toList();
    }
    return java.util.Arrays.stream(String.valueOf(value).split("[、,，]"))
        .map(this::trimToNull)
        .filter(Objects::nonNull)
        .filter(item -> !"-".equals(item))
        .toList();
  }

  protected record DetailRecordPage(
      List<Map<String, Object>> records,
      long total,
      int page,
      int size,
      Map<String, List<String>> quickFilterOptions) {
  }
}
