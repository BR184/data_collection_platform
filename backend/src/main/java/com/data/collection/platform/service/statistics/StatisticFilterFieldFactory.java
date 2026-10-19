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

  /**
   * 构造带事实候选的文本条件字段。
   *
   * <p>用于字段本身合法、但快捷入口需要可点选候选的场景（例如同范围内的人员）：关系清单仍取
   * {@code TEXT_OPERATORS}，候选只提供取值，不改变条件求值语义。
   *
   * @param key 字段键，与条件求值描述符同源
   * @param label 字段展示名
   * @param width 控件宽度，可为空由前端按标签兜底
   * @param options 同范围事实候选，null 视为无候选
   * @return type=text 且 operators 为文本关系清单的字段定义
   */
  public static StatisticFilterField textWithCandidates(
      String key, String label, Integer width, List<StatisticFilterOption> options) {
    return new StatisticFilterField(
        key, label, "text", "", "", width, TEXT_OPERATORS, options == null ? List.of() : options);
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
