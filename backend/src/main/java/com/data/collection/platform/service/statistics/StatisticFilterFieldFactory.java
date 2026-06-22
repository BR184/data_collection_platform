package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterField;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import java.util.List;

public final class StatisticFilterFieldFactory {
  private static final List<String> TEXT_OPERATORS = List.of("eq", "ne", "contains", "isEmpty", "isNotEmpty");
  private static final List<String> NUMBER_OPERATORS = List.of("eq", "gt", "gte", "lt", "lte", "between");
  private static final List<String> DATETIME_OPERATORS = List.of("year", "month", "day", "at", "before", "after", "between");

  private StatisticFilterFieldFactory() {}

  public static StatisticFilterField select(String key, String label, Integer width, List<StatisticFilterOption> options) {
    return new StatisticFilterField(key, label, "select", "", "", width, List.of("eq", "ne"), options == null ? List.of() : options);
  }

  public static StatisticFilterField selectLabelGroup(String key, String label, Integer width, List<StatisticFilterOption> options) {
    return new StatisticFilterField(
        key,
        label,
        "select",
        "",
        "",
        width,
        List.of("eq", "ne"),
        options == null ? List.of() : options,
        true,
        "STRING");
  }

  public static StatisticFilterField text(String key, String label, Integer width) {
    return new StatisticFilterField(key, label, "text", "", "", width, TEXT_OPERATORS, List.of());
  }

  public static StatisticFilterField textLabelGroup(String key, String label, Integer width) {
    return new StatisticFilterField(key, label, "text", "", "", width, TEXT_OPERATORS, List.of(), true, "STRING");
  }

  public static StatisticFilterField number(String key, String label, Integer width) {
    return new StatisticFilterField(key, label, "number", "", "", width, NUMBER_OPERATORS, List.of());
  }

  public static StatisticFilterField datetime(String key, String label, Integer width) {
    return new StatisticFilterField(key, label, "datetime", "", "", width, DATETIME_OPERATORS, List.of());
  }
}
