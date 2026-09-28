package com.data.collection.platform.entity.statistics;

import java.util.List;
import java.util.Map;

/**
 * 统计板下钻明细响应。
 *
 * <p>{@code collections} 列出当前指标允许的全部集合，{@code collection} 是本次实际返回的集合键；
 * 旧看板显式声明单一 {@code DETAIL} 集合。空集合也返回解释、列定义与 {@code total=0}，
 * 不以空记录掩盖"该集合确实没有议题"。
 */
public record StatisticDetailResponse(
    String title,
    String description,
    List<StatisticDetailCollection> collections,
    String collection,
    List<StatisticDetailColumn> columns,
    List<Map<String, Object>> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder,
    Map<String, List<String>> quickFilterOptions) {

  public StatisticDetailResponse {
    collections = collections == null ? List.of() : List.copyOf(collections);
    if (collection == null || collection.isBlank()) {
      throw new IllegalArgumentException("下钻响应必须声明当前集合");
    }
  }
}
