package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDateTime;
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
    String handlerName,
    String assigneeName,
    String directTestingPhase,
    String fixUser,
    String delayCause,
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
    String sortOrder,
    CustomerIssueRecordFilters.CcProductFilters ccProductFilters,
    LocalDateTime retentionAsOf) {

  public IssueFactRecordPageQuery {
    testingPhases = testingPhases == null ? List.of() : List.copyOf(testingPhases);
    ccProductFilters =
        ccProductFilters == null
            ? CustomerIssueRecordFilters.CcProductFilters.empty()
            : ccProductFilters;
  }

  public enum Scope {
    ALL,
    CUSTOMER_PROJECT,
    CUSTOMER,
    SYSTEM_TEST
  }
}
