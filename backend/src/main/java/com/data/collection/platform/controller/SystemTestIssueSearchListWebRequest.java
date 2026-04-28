package com.data.collection.platform.controller;

public class SystemTestIssueSearchListWebRequest extends IssueFactRecordListWebRequest {
  private String testingPhase;
  private String authorName;
  private String assigneeName;

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
}
