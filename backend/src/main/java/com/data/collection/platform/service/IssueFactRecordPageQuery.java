package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;

public record IssueFactRecordPageQuery(
    Scope scope,
    IssueFactRecordListRequest listRequest,
    StatisticFilterGroup filterGroup,
    String reasonCategory,
    String illegalReason,
    String testingPhase,
    List<String> testingPhases,
    String authorName,
    String assigneeName,
    boolean delayOnly,
    boolean illegalOnly,
    boolean excludeExcluded,
    boolean excludeRejectedBugStatus,
    boolean supportedSystemIllegalReasonsOnly,
    boolean supportedCustomerIllegalReasonsOnly,
    boolean useDisplayModuleFilter,
    boolean useFullTestingPhaseFilter,
    int page,
    int size,
    String sortField,
    String sortOrder) {

  public IssueFactRecordPageQuery {
    testingPhases = testingPhases == null ? List.of() : List.copyOf(testingPhases);
  }

  public enum Scope {
    ALL,
    CUSTOMER,
    SYSTEM_TEST
  }
}
