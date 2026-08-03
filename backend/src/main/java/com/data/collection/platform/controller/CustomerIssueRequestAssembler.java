package com.data.collection.platform.controller;

import com.data.collection.platform.service.CustomerIssueIllegalRecordQueryRequest;
import com.data.collection.platform.service.CustomerIssueRecordFilters;
import com.data.collection.platform.service.CustomerIssueRecordQueryRequest;
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
        new CustomerIssueRecordFilters(
            request.getReasonCategory(),
            request.getAuthorName(),
            request.getHandlerName(),
            request.getAssigneeName(),
            request.getTestingPhase(),
            request.getFixUser(),
            request.getDelayCause(),
            new CustomerIssueRecordFilters.CcProductFilters(
                request.getCustomerName(),
                request.getPlannedResolutionAtStart(),
                request.getPlannedResolutionAtEnd(),
                request.getPlannedMergeVersionBranch(),
                request.getRetentionHoursMin(),
                request.getRetentionHoursMax())),
        request.getFilterGroup());
  }

  public CustomerIssueIllegalRecordQueryRequest toIllegalRecordQueryRequest(
      CustomerIssueIllegalRecordListWebRequest request) {
    return new CustomerIssueIllegalRecordQueryRequest(
        listRequestAssembler.toServiceRequest(request),
        request.getIllegalReason(),
        null,
        request.getAuthorName(),
        request.getAssigneeName(),
        request.getFilterGroup());
  }

}
