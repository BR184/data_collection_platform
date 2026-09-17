package com.data.collection.platform.entity.statistics;

import java.util.Map;

public record StatisticCellData(
    String columnKey,
    // 可空：null 表示该单元格无可计算数值（如比率分母为 0 的“无数据”），与真实 0 严格区分，供排序将无数据恒置底。
    Long numericValue,
    String displayValue,
    boolean drilldown,
    String detailViewKey,
    Map<String, String> detailParams) {
}
