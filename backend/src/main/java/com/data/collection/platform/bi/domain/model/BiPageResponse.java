package com.data.collection.platform.bi.domain.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 冻结单页来源版本、状态、追溯信息和强类型页面数据的统一信封。 */
public record BiPageResponse<T>(
    String pageKey,
    BiDataStatus status,
    String sourceVersion,
    String snapshotId,
    String ruleVersion,
    OffsetDateTime generatedAt,
    List<BiPageSection> sections,
    List<BiMetricTrace> traces,
    T data) {
  public BiPageResponse {
    // 本层流程：页面服务完成读取和计算后，在这里统一封装状态、来源版本、规则版本、
    // 快照和追溯信息；前端只消费这个响应信封，不直接推断后端内部状态。
    // 响应信封是页面和前端之间的唯一状态边界，集合统一转为不可变副本。
    sections = sections == null ? List.of() : List.copyOf(sections);
    traces = traces == null ? List.of() : List.copyOf(traces);
  }

  /** 构造普通页面响应，生成时间不参与来源版本计算。 */
  public static <T> BiPageResponse<T> create(
      String pageKey,
      BiDataStatus status,
      String sourceVersion,
      String snapshotId,
      String ruleVersion,
      List<BiPageSection> sections,
      List<BiMetricTrace> traces,
      T data) {
    // generatedAt 只记录本次响应生成时间；数据一致性由 sourceVersion/snapshotId 保证。
    return new BiPageResponse<>(
        pageKey,
        status,
        sourceVersion,
        snapshotId,
        ruleVersion,
        OffsetDateTime.now(ZoneOffset.UTC),
        sections,
        traces,
        data);
  }

  /** 在 BI Runtime 或页面计算失败时保留页面壳并返回明确错误状态。 */
  public static <T> BiPageResponse<T> error(String pageKey, String message) {
    // 失败也返回同一信封，前端因此可以保留页面结构并准确显示错误，而不是误当成空数据。
    return create(
        pageKey,
        BiDataStatus.ERROR,
        "",
        "",
        "",
        List.of(new BiPageSection("page", "页面数据", BiDataStatus.ERROR, message)),
        List.of(),
        null);
  }
}
