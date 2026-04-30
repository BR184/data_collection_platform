package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;

public record IssueFactRecordPageQuery(
    Scope scope,
    IssueFactRecordListRequest listRequest,
    StatisticFilterGroup filterGroup,
    String reasonCategory,
    String illegalReason,
    String testingPhase,
    String authorName,
    String assigneeName,
    boolean delayOnly,
    boolean illegalOnly,
    boolean excludeExcluded,
    boolean supportedSystemIllegalReasonsOnly,
    boolean useDisplayModuleFilter,
    int page,
    int size,
    String sortField,
    String sortOrder) {

  public enum Scope {
    CUSTOMER,
    SYSTEM_TEST
  }
}
