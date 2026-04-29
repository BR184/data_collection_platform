package com.data.collection.platform.service;

public record IssueFactRecordPageQuery(
    Scope scope,
    IssueFactRecordListRequest listRequest,
    String reasonCategory,
    String illegalReason,
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
