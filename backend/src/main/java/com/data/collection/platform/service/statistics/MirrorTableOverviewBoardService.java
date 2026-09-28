package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticDetailColumn;
import com.data.collection.platform.entity.statistics.StatisticDetailCollection;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterField;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.mapper.MirrorTableOverviewMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class MirrorTableOverviewBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport {
  private static final String BOARD_KEY = "mirror-table-overview";
  private static final String DETAIL_VIEW_KEY = "mirror-record-list";
  private static final String RULE_VERSION = "mirror-table-overview@2026-04-07-v1";
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      List.of(
          new StatisticDetailColumn("id", "镜像 ID", 96, null, true),
          new StatisticDetailColumn("tableName", "表名", 180, null, true),
          new StatisticDetailColumn("recordKey", "记录键", 220, null, true),
          new StatisticDetailColumn("updatedAtSource", "源更新时间", 180, null, true),
          new StatisticDetailColumn("syncedAt", "同步时间", 180, null, true),
          new StatisticDetailColumn("rowData", "原始数据", null, 360, false));
  private static final Map<String, String> SORT_FIELD_MAPPING =
      Map.of(
          "id", "id",
          "tableName", "table_name",
          "recordKey", "record_key",
          "updatedAtSource", "updated_at_source",
          "syncedAt", "synced_at");

  private final MirrorTableOverviewMapper overviewMapper;

  public MirrorTableOverviewBoardService(
      MirrorTableOverviewMapper overviewMapper,
      JsonUtils jsonUtils) {
    super(jsonUtils);
    this.overviewMapper = overviewMapper;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return buildDefinition(queryTableOptions());
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    long startedAt = System.currentTimeMillis();
    List<StatisticFilterOption> tableOptions = queryTableOptions();
    String tableName = extractTableNameFilter(filterGroup);
    List<Map<String, Object>> summaryRows = overviewMapper.selectSummaryRows(tableName);
    List<Map<String, Object>> filteredRows = filterSummaryRows(summaryRows, filterGroup);
    List<StatisticRowData> boardRows = filteredRows.stream().map(this::toStatisticRow).toList();

    StatisticBoardDefinition definition = buildDefinition(tableOptions);
    Map<String, String> appliedFilters = new LinkedHashMap<>();
    appliedFilters.put("tableName", tableName == null ? "" : tableName);

    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownColumnCount =
        definition.columnGroups().stream()
            .flatMap(group -> group.leafColumns().stream())
            .mapToInt(column -> column.drilldown() ? 1 : 0)
            .sum();

    StatisticBoardMeta meta =
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            boardRows.size(),
            columnCount,
            drilldownColumnCount);
    return new StatisticBoardResponse(definition, appliedFilters, filterGroup, boardRows, meta);
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    String tableName = trimToNull(request.rowKey());
    if (tableName == null) {
      throw new BizException("明细查询缺少行维度标识");
    }

    String columnKey = trimToNull(request.columnKey());
    if (columnKey == null) {
      throw new BizException("明细查询缺少列维度标识");
    }

    int page = request.page() <= 0 ? 1 : request.page();
    int size = request.size() <= 0 ? 10 : request.size();
    String sortField = SORT_FIELD_MAPPING.getOrDefault(request.sortField(), "synced_at");
    String sortOrder = "ascending".equalsIgnoreCase(request.sortOrder()) ? "asc" : "desc";

    if (!isRowVisible(tableName, filterGroup)) {
      return emptyDetail(tableName, columnKey, page, size, request);
    }

    long total = overviewMapper.countDetails(tableName, columnKey);
    List<Map<String, Object>> records =
        overviewMapper.selectDetails(tableName, columnKey, sortField, sortOrder, size, (page - 1) * size);

    return new StatisticDetailResponse(
        buildDetailTitle(tableName, columnKey),
        "展示当前统计单元格对应的镜像原始记录。",
        List.of(StatisticDetailCollection.detailList()),
        StatisticDetailCollection.DETAIL_KEY,
        DETAIL_COLUMNS,
        records,
        total,
        page,
        size,
        request.sortField() == null ? "syncedAt" : request.sortField(),
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending",
        Map.of());
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    List<Map<String, Object>> summaryRows = overviewMapper.selectSummaryRows(extractTableNameFilter(parseFilterGroup(filters, buildDefinition())));
    long totalRows = summaryRows.size();
    long rowsWithSourceUpdate = summaryRows.stream().filter(row -> asLong(row.get("withSourceUpdate")) > 0).count();
    long rowsWithRecentSync = summaryRows.stream().filter(row -> asLong(row.get("syncedIn24h")) > 0).count();

    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "镜像表基础统计规则说明",
        RULE_VERSION,
        "当前统计直接建立在本地镜像表汇总结果上，不引入业务标签规则。",
        "该统计板用于解释镜像覆盖范围和同步时效，核心逻辑是镜像表聚合而不是业务规则归类。",
        List.of(
            new StatisticRuleFlowStep(
                "load-summary",
                "加载镜像表汇总",
                "从镜像汇总 Mapper 读取各张镜像表的记录规模、源更新时间和同步时效数据。",
                totalRows,
                totalRows,
                summaryRows.stream().limit(3).map(this::toSummarySample).toList()),
            new StatisticRuleFlowStep(
                "source-update-coverage",
                "统计源更新时间覆盖",
                "按照每张镜像表是否存在源更新时间字段数据，展示覆盖情况。",
                totalRows,
                rowsWithSourceUpdate,
                summaryRows.stream().filter(row -> asLong(row.get("withSourceUpdate")) > 0).limit(3).map(this::toSummarySample).toList()),
            new StatisticRuleFlowStep(
                "recent-sync-coverage",
                "统计最近同步覆盖",
                "按 24 小时内是否有同步记录展示同步时效。",
                totalRows,
                rowsWithRecentSync,
                summaryRows.stream().filter(row -> asLong(row.get("syncedIn24h")) > 0).limit(3).map(this::toSummarySample).toList())),
        List.of(
            new StatisticRuleMetricDefinition("totalRecords", "总记录数", "统计当前镜像表中的记录总数。", "总记录数 = 镜像表记录数", "来自汇总 Mapper。"),
            new StatisticRuleMetricDefinition("withSourceUpdate", "有源更新时间", "统计具备源更新时间的记录数。", "有源更新时间 = updated_at_source 不为空的记录数", "用于判断源侧更新追踪能力。"),
            new StatisticRuleMetricDefinition("withoutSourceUpdate", "缺少源更新时间", "统计缺少源更新时间的记录数。", "缺少源更新时间 = 总记录数 - 有源更新时间", "用于识别不可追踪源更新时间的表。"),
            new StatisticRuleMetricDefinition("syncedIn24h", "24小时内同步", "统计最近 24 小时内完成同步的记录数。", "24小时内同步 = synced_at >= now - 24h 的记录数", "用于观察同步时效。"),
            new StatisticRuleMetricDefinition("staleSync", "24小时前同步", "统计最近同步时间早于 24 小时的记录数。", "24小时前同步 = 总记录数 - 24小时内同步", "用于定位陈旧镜像。")),
        null);
  }

  private StatisticRuleFlowStepSample toSummarySample(Map<String, Object> row) {
    String tableName = String.valueOf(row.get("tableName"));
    return new StatisticRuleFlowStepSample(
        tableName,
        "总记录数=" + asLong(row.get("totalRecords"))
            + "，24小时内同步=" + asLong(row.get("syncedIn24h"))
            + "，缺少源更新时间=" + asLong(row.get("withoutSourceUpdate")));
  }

  private StatisticBoardDefinition buildDefinition(List<StatisticFilterOption> tableOptions) {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "GitLab 镜像表基础统计",
        "基于已同步到本地的镜像记录，对各类 GitLab 源表的记录规模和同步覆盖情况进行汇总展示。",
        "查询与操作",
        "按真实镜像字段配置筛选条件。当前页面只使用镜像表中的标准字段，不引入额外业务规则。",
        "统计对象",
        List.of(
            new StatisticFilterField("tableName", "统计对象", "select", "", "", 220, List.of("eq", "ne"), tableOptions),
            new StatisticFilterField("totalRecords", "总记录数", "number", "", "", 160, List.of("eq", "gt", "gte", "lt", "lte", "between"), List.of()),
            new StatisticFilterField("withSourceUpdate", "有源更新时间", "number", "", "", 170, List.of("eq", "gt", "gte", "lt", "lte", "between"), List.of()),
            new StatisticFilterField("withoutSourceUpdate", "缺少源更新时间", "number", "", "", 180, List.of("eq", "gt", "gte", "lt", "lte", "between"), List.of()),
            new StatisticFilterField("syncedIn24h", "24小时内同步", "number", "", "", 170, List.of("eq", "gt", "gte", "lt", "lte", "between"), List.of()),
            new StatisticFilterField("staleSync", "24小时前同步", "number", "", "", 170, List.of("eq", "gt", "gte", "lt", "lte", "between"), List.of()),
            new StatisticFilterField("lastSyncedAt", "最近同步时间", "datetime", "", "", 220, List.of("year", "month", "day", "at", "before", "after", "between"), List.of()),
            new StatisticFilterField("lastSourceUpdatedAt", "最近源更新时间", "datetime", "", "", 220, List.of("year", "month", "day", "at", "before", "after", "between"), List.of())),
        List.of(
            new StatisticColumnGroup(
                "scale",
                "记录规模",
                List.of(new StatisticColumnLeaf("totalRecords", "总记录数", true, "count"))),
            new StatisticColumnGroup(
                "sourceTime",
                "源更新时间",
                List.of(
                    new StatisticColumnLeaf("withSourceUpdate", "有源更新时间", true, "count"),
                    new StatisticColumnLeaf("withoutSourceUpdate", "缺少源更新时间", true, "count"))),
            new StatisticColumnGroup(
                "syncStatus",
                "同步时效",
                List.of(
                    new StatisticColumnLeaf("syncedIn24h", "24小时内同步", true, "count"),
                    new StatisticColumnLeaf("staleSync", "24小时前同步", true, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前筛选条件下没有可展示的镜像统计结果。");
  }

  private List<StatisticFilterOption> queryTableOptions() {
    return overviewMapper.selectTableNames().stream()
        .map(tableName -> new StatisticFilterOption(tableName, tableName))
        .toList();
  }

  private List<Map<String, Object>> filterSummaryRows(List<Map<String, Object>> rows, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return rows;
    }
    return rows.stream().filter(row -> matchesRow(row, filterGroup)).toList();
  }

  private boolean matchesRow(Map<String, Object> row, StatisticFilterGroup filterGroup) {
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    boolean matched = !isOr;
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean result = matchesCondition(row, condition);
      if (isOr) {
        matched = matched || result;
        if (matched) {
          return true;
        }
      } else {
        matched = matched && result;
        if (!matched) {
          return false;
        }
      }
    }
    return matched;
  }

  private boolean matchesCondition(Map<String, Object> row, StatisticFilterCondition condition) {
    return switch (condition.fieldKey()) {
      case "tableName" -> matchesText(String.valueOf(row.get("tableName")), condition);
      case "totalRecords", "withSourceUpdate", "withoutSourceUpdate", "syncedIn24h", "staleSync" ->
          matchesNumber(asLong(row.get(condition.fieldKey())), condition);
      case "lastSyncedAt", "lastSourceUpdatedAt" ->
          matchesDateTime(asLocalDateTime(row.get(condition.fieldKey())), condition);
      default -> true;
    };
  }

  private boolean matchesText(String source, StatisticFilterCondition condition) {
    String left = source == null ? "" : source;
    String right = Objects.requireNonNullElse(condition.value(), "");
    return switch (condition.operator()) {
      case "eq" -> left.equals(right);
      case "ne" -> !left.equals(right);
      case "contains" -> left.contains(right);
      case "notContains" -> !left.contains(right);
      default -> true;
    };
  }

  private boolean matchesNumber(long source, StatisticFilterCondition condition) {
    long target = Long.parseLong(condition.value());
    return switch (condition.operator()) {
      case "eq" -> source == target;
      case "gt" -> source > target;
      case "gte" -> source >= target;
      case "lt" -> source < target;
      case "lte" -> source <= target;
      case "between" -> source >= target && source <= Long.parseLong(condition.secondaryValue());
      default -> true;
    };
  }

  private boolean matchesDateTime(LocalDateTime source, StatisticFilterCondition condition) {
    if (source == null) {
      return false;
    }
    return switch (condition.operator()) {
      case "year" -> source.getYear() == Integer.parseInt(condition.value());
      case "month" -> YearMonth.from(source).equals(YearMonth.parse(condition.value()));
      case "day" -> source.toLocalDate().equals(LocalDate.parse(condition.value()));
      case "at" -> source.equals(parseDateTime(condition.value()));
      case "before" -> source.isBefore(parseDateTime(condition.value()));
      case "after" -> source.isAfter(parseDateTime(condition.value()));
      case "between" -> {
        LocalDateTime start = parseDateTime(condition.value());
        LocalDateTime end = parseDateTime(condition.secondaryValue());
        yield (source.isEqual(start) || source.isAfter(start)) && (source.isEqual(end) || source.isBefore(end));
      }
      default -> true;
    };
  }

  private long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private LocalDateTime asLocalDateTime(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toLocalDateTime();
    }
    String raw = String.valueOf(value).replace('T', ' ');
    try {
      return LocalDateTime.parse(raw.substring(0, 19), DATE_TIME_FORMATTER);
    } catch (RuntimeException ex) {
      throw new BizException("无法解析时间值: " + value);
    }
  }

  private LocalDateTime parseDateTime(String value) {
    try {
      return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
    } catch (DateTimeParseException ex) {
      throw new BizException("时间筛选值格式不正确: " + value);
    }
  }

  private StatisticRowData toStatisticRow(Map<String, Object> row) {
    String tableName = String.valueOf(row.get("tableName"));
    return new StatisticRowData(
        tableName,
        tableName,
        List.of(
            buildCell(tableName, "totalRecords", row.get("totalRecords")),
            buildCell(tableName, "withSourceUpdate", row.get("withSourceUpdate")),
            buildCell(tableName, "withoutSourceUpdate", row.get("withoutSourceUpdate")),
            buildCell(tableName, "syncedIn24h", row.get("syncedIn24h")),
            buildCell(tableName, "staleSync", row.get("staleSync"))));
  }

  private StatisticCellData buildCell(String tableName, String columnKey, Object value) {
    long numericValue = value == null ? 0L : ((Number) value).longValue();
    boolean canDrilldown = StatisticDrilldownSupport.legacyCellDrilldown(true, numericValue);
    return new StatisticCellData(
        columnKey,
        numericValue,
        String.valueOf(numericValue),
        canDrilldown,
        canDrilldown ? DETAIL_VIEW_KEY : null,
        Map.of("tableName", tableName, "columnKey", columnKey));
  }

  private String extractTableNameFilter(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return null;
    }
    return filterGroup.conditions().stream()
        .filter(condition -> "tableName".equals(condition.fieldKey()) && "eq".equals(condition.operator()))
        .map(StatisticFilterCondition::value)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private boolean isRowVisible(String rowKey, StatisticFilterGroup filterGroup) {
    String tableName = extractTableNameFilter(filterGroup);
    return filterSummaryRows(overviewMapper.selectSummaryRows(tableName), filterGroup).stream()
        .anyMatch(row -> rowKey.equals(String.valueOf(row.get("tableName"))));
  }

  private StatisticDetailResponse emptyDetail(
      String tableName,
      String columnKey,
      int page,
      int size,
      StatisticDetailRequest request) {
    return new StatisticDetailResponse(
        buildDetailTitle(tableName, columnKey),
        "当前筛选条件下没有可展示的明细记录。",
        List.of(StatisticDetailCollection.detailList()),
        StatisticDetailCollection.DETAIL_KEY,
        DETAIL_COLUMNS,
        List.of(),
        0,
        page,
        size,
        request.sortField() == null ? "syncedAt" : request.sortField(),
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending",
        Map.of());
  }

  private String buildDetailTitle(String tableName, String columnKey) {
    return tableName
        + " / "
        + switch (columnKey) {
          case "totalRecords" -> "总记录数";
          case "withSourceUpdate" -> "有源更新时间";
          case "withoutSourceUpdate" -> "缺少源更新时间";
          case "syncedIn24h" -> "24小时内同步";
          case "staleSync" -> "24小时前同步";
          default -> columnKey;
        };
  }
}
