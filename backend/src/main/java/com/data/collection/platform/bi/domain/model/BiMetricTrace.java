package com.data.collection.platform.bi.domain.model;

import java.util.List;

/** 页面指标对来源字段和人工确认公式编号的追溯记录。 */
public record BiMetricTrace(
    List<String> metricIds,
    String sourcePlatform,
    List<SourceField> sourceFields,
    String formula,
    String calculationOwner) {
  public BiMetricTrace {
    metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
    sourceFields = sourceFields == null ? List.of() : List.copyOf(sourceFields);
  }

  /** 来源平台字段的中英文语义。 */
  public record SourceField(String chineseName, String englishName) {}
}
