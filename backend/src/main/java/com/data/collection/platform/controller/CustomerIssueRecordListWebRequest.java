package com.data.collection.platform.controller;

public class CustomerIssueRecordListWebRequest extends IssueFactRecordListWebRequest {
  private String topic;
  private String reasonCategory;
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

  public String getFilterGroup() {
    return filterGroup;
  }

  public void setFilterGroup(String filterGroup) {
    this.filterGroup = filterGroup;
  }
}
