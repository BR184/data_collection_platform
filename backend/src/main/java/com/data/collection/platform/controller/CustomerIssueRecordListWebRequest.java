package com.data.collection.platform.controller;

public class CustomerIssueRecordListWebRequest extends IssueFactRecordListWebRequest {
  private String topic;
  private String reasonCategory;
  private String authorName;
  private String assigneeName;
  private String filterGroup;

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public String getReasonCategory() {
    return reasonCategory;
  }

  public void setReasonCategory(String reasonCategory) {
    this.reasonCategory = reasonCategory;
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
