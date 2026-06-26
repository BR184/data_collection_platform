package com.data.collection.platform.controller;

public class CodeReviewIllegalRecordFilterOptionsWebRequest {
  private Long projectId;
  private String projectName;
  private String source;

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
