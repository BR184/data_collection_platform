package com.data.collection.platform.controller;

public class CustomerIssueRecordListWebRequest extends IssueFactRecordListWebRequest {
  private String topic;
  private String reasonCategory;
  private String authorName;
  private String handlerName;
  private String assigneeName;
  private String testingPhase;
  private String fixUser;
  private String delayCause;
  private String customerName;
  private String plannedResolutionAtStart;
  private String plannedResolutionAtEnd;
  private String plannedMergeVersionBranch;
  private Long retentionHoursMin;
  private Long retentionHoursMax;
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

  public String getHandlerName() {
    return handlerName;
  }

  public void setHandlerName(String handlerName) {
    this.handlerName = handlerName;
  }

  public String getAssigneeName() {
    return assigneeName;
  }

  public void setAssigneeName(String assigneeName) {
    this.assigneeName = assigneeName;
  }

  public String getTestingPhase() {
    return testingPhase;
  }

  public void setTestingPhase(String testingPhase) {
    this.testingPhase = testingPhase;
  }

  public String getFixUser() {
    return fixUser;
  }

  public void setFixUser(String fixUser) {
    this.fixUser = fixUser;
  }

  public String getDelayCause() {
    return delayCause;
  }

  public void setDelayCause(String delayCause) {
    this.delayCause = delayCause;
  }

  public String getCustomerName() {
    return customerName;
  }

  public void setCustomerName(String customerName) {
    this.customerName = customerName;
  }

  public String getPlannedResolutionAtStart() {
    return plannedResolutionAtStart;
  }

  public void setPlannedResolutionAtStart(String plannedResolutionAtStart) {
    this.plannedResolutionAtStart = plannedResolutionAtStart;
  }

  public String getPlannedResolutionAtEnd() {
    return plannedResolutionAtEnd;
  }

  public void setPlannedResolutionAtEnd(String plannedResolutionAtEnd) {
    this.plannedResolutionAtEnd = plannedResolutionAtEnd;
  }

  public String getPlannedMergeVersionBranch() {
    return plannedMergeVersionBranch;
  }

  public void setPlannedMergeVersionBranch(String plannedMergeVersionBranch) {
    this.plannedMergeVersionBranch = plannedMergeVersionBranch;
  }

  public Long getRetentionHoursMin() {
    return retentionHoursMin;
  }

  public void setRetentionHoursMin(Long retentionHoursMin) {
    this.retentionHoursMin = retentionHoursMin;
  }

  public Long getRetentionHoursMax() {
    return retentionHoursMax;
  }

  public void setRetentionHoursMax(Long retentionHoursMax) {
    this.retentionHoursMax = retentionHoursMax;
  }

  public String getFilterGroup() {
    return filterGroup;
  }

  public void setFilterGroup(String filterGroup) {
    this.filterGroup = filterGroup;
  }
}
