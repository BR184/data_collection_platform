package com.data.collection.platform.controller;

public class CustomerIssueIllegalRecordSingleRefreshWebRequest {
  private String source;
  private Long projectId;
  private Long issueIid;

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public Long getIssueIid() {
    return issueIid;
  }

  public void setIssueIid(Long issueIid) {
    this.issueIid = issueIid;
  }
}
