package com.data.collection.platform.entity.statistics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计板产出。
 *
 * <p>{@code dataAsOf} 与 {@code pendingUpdates} 只在来源尚未收敛到最新变化版本时出现：此时返回的是
 * 上一个完整发布点的结果，{@code dataAsOf} 是该结果的数据时刻，{@code pendingUpdates} 是尚未发布的
 * 稳定根数量。两者必须同时存在或同时缺失，权威产出不携带任何提示字段，因此正常响应与历史契约逐字一致。
 */
public record StatisticBoardResponse(
    StatisticBoardDefinition definition,
    Map<String, String> appliedFilters,
    StatisticFilterGroup appliedFilterGroup,
    List<StatisticRowData> rows,
    StatisticBoardMeta meta,
    @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime dataAsOf,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long pendingUpdates) {

  public StatisticBoardResponse {
    if ((dataAsOf == null) != (pendingUpdates == null)) {
      throw new IllegalArgumentException("来源新鲜度提示必须同时包含数据时刻与待更新数量");
    }
    if (pendingUpdates != null && pendingUpdates <= 0L) {
      throw new IllegalArgumentException("来源新鲜度提示必须给出正数待更新数量");
    }
  }

  /**
   * 返回附带来源新鲜度提示的副本：数据时刻取自本结果自身的生成时间。
   *
   * @param pendingUpdates 尚未发布到最新变化版本的稳定根数量；{@code 0} 表示权威产出，原样返回
   */
  public StatisticBoardResponse withPendingUpdates(long pendingUpdates) {
    if (pendingUpdates <= 0L) {
      return this;
    }
    return new StatisticBoardResponse(
        definition,
        appliedFilters,
        appliedFilterGroup,
        rows,
        meta,
        meta.generatedAt(),
        pendingUpdates);
  }
}
