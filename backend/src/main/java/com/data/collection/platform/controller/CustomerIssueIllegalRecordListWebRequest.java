package com.data.collection.platform.controller;

public class CustomerIssueIllegalRecordListWebRequest extends IssueFactRecordListWebRequest {
  private String illegalReason;
  private String testingPhase;
  private String filterGroup;

  public String getIllegalReason() {
    return illegalReason;
  }

  public void setIllegalReason(String illegalReason) {
    this.illegalReason = illegalReason;
  }

  public String getTestingPhase() {
    return testingPhase;
  }

  public void setTestingPhase(String testingPhase) {
    this.testingPhase = testingPhase;
  }

  public String getFilterGroup() {
    return filterGroup;
  }

  public void setFilterGroup(String filterGroup) {
    this.filterGroup = filterGroup;
  }
}
