package com.data.collection.platform.controller;

public class IssueFactRecordListWebRequest {
  private Long projectId;
  private String keyword;
  private String issueIid;
  private String title;
  private String projectName;
  private String moduleName;
  private String severityLevel;
  private String priorityLevel;
  private String issueState;
  private String bugStatus;
  private String category;
  private String milestoneTitle;
  private String createdAtStart;
  private String createdAtEnd;
  private String updatedAtStart;
  private String updatedAtEnd;
  private int page = 1;
  private int size = 20;
  private String sortBy;
  private String sortOrder;

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getKeyword() {
    return keyword;
  }

  public void setKeyword(String keyword) {
    this.keyword = keyword;
  }

  public String getIssueIid() {
    return issueIid;
  }

  public void setIssueIid(String issueIid) {
    this.issueIid = issueIid;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getModuleName() {
    return moduleName;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public String getSeverityLevel() {
    return severityLevel;
  }

  public void setSeverityLevel(String severityLevel) {
    this.severityLevel = severityLevel;
  }

  public String getPriorityLevel() {
    return priorityLevel;
  }

  public void setPriorityLevel(String priorityLevel) {
    this.priorityLevel = priorityLevel;
  }

  public String getIssueState() {
    return issueState;
  }

  public void setIssueState(String issueState) {
    this.issueState = issueState;
  }

  public String getBugStatus() {
    return bugStatus;
  }

  public void setBugStatus(String bugStatus) {
    this.bugStatus = bugStatus;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getMilestoneTitle() {
    return milestoneTitle;
  }

  public void setMilestoneTitle(String milestoneTitle) {
    this.milestoneTitle = milestoneTitle;
  }

  public String getCreatedAtStart() {
    return createdAtStart;
  }

  public void setCreatedAtStart(String createdAtStart) {
    this.createdAtStart = createdAtStart;
  }

  public String getCreatedAtEnd() {
    return createdAtEnd;
  }

  public void setCreatedAtEnd(String createdAtEnd) {
    this.createdAtEnd = createdAtEnd;
  }

  public String getUpdatedAtStart() {
    return updatedAtStart;
  }

  public void setUpdatedAtStart(String updatedAtStart) {
    this.updatedAtStart = updatedAtStart;
  }

  public String getUpdatedAtEnd() {
    return updatedAtEnd;
  }

  public void setUpdatedAtEnd(String updatedAtEnd) {
    this.updatedAtEnd = updatedAtEnd;
  }

  public int getPage() {
    return page;
  }

  public void setPage(int page) {
    this.page = page;
  }

  public int getSize() {
    return size;
  }

  public void setSize(int size) {
    this.size = size;
  }

  public String getSortBy() {
    return sortBy;
  }

  public void setSortBy(String sortBy) {
    this.sortBy = sortBy;
  }

  public String getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(String sortOrder) {
    this.sortOrder = sortOrder;
  }
}
