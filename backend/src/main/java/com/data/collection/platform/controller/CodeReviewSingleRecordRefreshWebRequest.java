package com.data.collection.platform.controller;

public class CodeReviewSingleRecordRefreshWebRequest {
  private String source;
  private Long projectId;
  private Long mergeRequestIid;

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

  public Long getMergeRequestIid() {
    return mergeRequestIid;
  }

  public void setMergeRequestIid(Long mergeRequestIid) {
    this.mergeRequestIid = mergeRequestIid;
  }
}
