package com.data.collection.platform.bi.domain.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 单次请求冻结后的需求或设计评审来源快照。 */
public record BiReviewSource(
    String sourceVersion,
    String snapshotId,
    List<ReviewRecord> records) {
  public BiReviewSource {
    records = records == null ? List.of() : List.copyOf(records);
  }

  /** 计算评审页面所需的单条评审基础度量。 */
  public record ReviewRecord(
      long reviewId,
      LocalDate reviewDate,
      BiSourceDimension module,
      Long reviewedPages,
      BigDecimal workloadHours,
      long effectiveProblemCount,
      long documentSpecificationCount,
      long integrityCount,
      long functionalityCount,
      long feasibilityCount) {}
}
