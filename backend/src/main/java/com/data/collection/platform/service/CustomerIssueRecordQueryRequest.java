package com.data.collection.platform.service;

public record CustomerIssueRecordQueryRequest(
    String topic,
    IssueFactRecordListRequest listRequest,
    CustomerIssueRecordFilters filters,
    String filterGroupJson) {

  public CustomerIssueRecordQueryRequest {
    filters = filters == null ? CustomerIssueRecordFilters.empty() : filters;
  }
}
