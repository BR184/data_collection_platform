package com.data.collection.platform.controller;

import com.data.collection.platform.service.CustomerIssueIllegalRecordQueryRequest;
import com.data.collection.platform.service.CustomerIssueRecordQueryRequest;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.TextQuerySupport;
import org.springframework.stereotype.Component;

@Component
public class CustomerIssueRequestAssembler {
  private final IssueFactRecordListRequestAssembler listRequestAssembler;

  public CustomerIssueRequestAssembler(IssueFactRecordListRequestAssembler listRequestAssembler) {
    this.listRequestAssembler = listRequestAssembler;
  }

  public CustomerIssueRecordQueryRequest toRecordQueryRequest(CustomerIssueRecordListWebRequest request) {
    return new CustomerIssueRecordQueryRequest(
        request.getTopic(),
        listRequestAssembler.toServiceRequest(request),
        request.getReasonCategory(),
        request.getAuthorName(),
        request.getHandlerName(),
        request.getAssigneeName(),
        request.getTestingPhase(),
        request.getFixUser(),
        request.getDelayCause(),
        request.getFilterGroup(),
        request.getCustomerName());
  }

  public CustomerIssueIllegalRecordQueryRequest toIllegalRecordQueryRequest(
      CustomerIssueIllegalRecordListWebRequest request) {
    IssueFactRecordListRequest listRequest = listRequestAssembler.toServiceRequest(request);
    return new CustomerIssueIllegalRecordQueryRequest(
        withLegacyTestingPhaseAsMilestone(listRequest, request.getTestingPhase()),
        request.getIllegalReason(),
        null,
        request.getAuthorName(),
        request.getAssigneeName(),
        request.getFilterGroup());
  }

  private IssueFactRecordListRequest withLegacyTestingPhaseAsMilestone(
      IssueFactRecordListRequest request, String legacyTestingPhase) {
    String milestoneTitle = TextQuerySupport.trimToNull(request.milestoneTitle());
    String legacyMilestone = TextQuerySupport.trimToNull(legacyTestingPhase);
    if (milestoneTitle != null || legacyMilestone == null) {
      return request;
    }
    return new IssueFactRecordListRequest(
        request.projectId(),
        request.keyword(),
        request.searchType(),
        request.issueIid(),
        request.title(),
        request.projectName(),
        request.moduleName(),
        request.functionName(),
        request.severityLevel(),
        request.priorityLevel(),
        request.issueState(),
        request.bugStatus(),
        request.category(),
        legacyMilestone,
        request.createdAtStart(),
        request.createdAtEnd(),
        request.updatedAtStart(),
        request.updatedAtEnd(),
        request.sourceInstance(),
        request.page(),
        request.size(),
        request.sortField(),
        request.sortOrder());
  }
}
