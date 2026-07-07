package com.data.collection.platform.controller;

public class CustomerIssueIllegalRecordListWebRequest extends IssueFactRecordListWebRequest {
  private String illegalReason;
  private String testingPhase;
  private String authorName;
  private String assigneeName;
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

  public String getAuthorName() {
    return authorName;
  }

  public void setAuthorName(String authorName) {
    this.authorName = authorName;
  }

  public String getAssigneeName() {
    return assigneeName;
  }

  public void setAssigneeName(String assigneeName) {
    this.assigneeName = assigneeName;
  }

  public String getFilterGroup() {
    return filterGroup;
  }

  public void setFilterGroup(String filterGroup) {
    this.filterGroup = filterGroup;
  }
}
