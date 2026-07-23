package com.data.collection.platform.service;

import java.util.List;

/** 客户问题记录页缓存的静态事实投影。 */
public record CustomerIssueRecordPageSnapshot(
    List<IssueFactRecord> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder) {

  public CustomerIssueRecordPageSnapshot {
    records = records == null ? List.of() : List.copyOf(records);
  }
}
