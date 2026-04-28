package com.data.collection.platform.controller;

import com.data.collection.platform.service.SystemTestIllegalRecordQueryRequest;
import com.data.collection.platform.service.SystemTestIssueSearchQueryRequest;
import org.springframework.stereotype.Component;

@Component
public class QuestionMetricsRequestAssembler {
  private final IssueFactRecordListRequestAssembler listRequestAssembler;

  public QuestionMetricsRequestAssembler(IssueFactRecordListRequestAssembler listRequestAssembler) {
    this.listRequestAssembler = listRequestAssembler;
  }

  public SystemTestIssueSearchQueryRequest toIssueSearchQueryRequest(
      SystemTestIssueSearchListWebRequest request) {
    return new SystemTestIssueSearchQueryRequest(
        listRequestAssembler.toServiceRequest(request),
        request.getTestingPhase(),
        request.getAuthorName(),
        request.getAssigneeName());
  }

  public SystemTestIllegalRecordQueryRequest toIllegalRecordQueryRequest(
      SystemTestIllegalRecordListWebRequest request) {
    return new SystemTestIllegalRecordQueryRequest(
        listRequestAssembler.toServiceRequest(request),
        request.getTestingPhase(),
        request.getIllegalReason(),
        request.getAuthorName(),
        request.getAssigneeName(),
        request.getFilterGroup());
  }
}
