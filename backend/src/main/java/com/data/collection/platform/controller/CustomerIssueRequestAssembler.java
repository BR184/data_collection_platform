package com.data.collection.platform.controller;

import com.data.collection.platform.service.CustomerIssueIllegalRecordQueryRequest;
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
        request.getReasonCategory(),
        request.getFilterGroup());
  }

  public CustomerIssueIllegalRecordQueryRequest toIllegalRecordQueryRequest(
      CustomerIssueIllegalRecordListWebRequest request) {
    return new CustomerIssueIllegalRecordQueryRequest(
        listRequestAssembler.toServiceRequest(request),
        request.getIllegalReason(),
        request.getFilterGroup());
  }
}
