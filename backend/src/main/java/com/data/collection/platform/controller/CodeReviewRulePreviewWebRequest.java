package com.data.collection.platform.controller;

import com.data.collection.platform.entity.CodeReviewRuleConfig;

public class CodeReviewRulePreviewWebRequest {
  private Long projectId;
  private String repositoryName;
  private String mergedAtStart;
  private String mergedAtEnd;
  private String keyword;
  private String projectName;
  private String requestType;
  private String targetBranch;
  private String mergedBy;
  private String moduleName;
  private String illegalType;
  private String mergeRequestIid;
  private String owner;
  private CodeReviewRuleConfig ruleConfig;

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getMergedAtStart() {
    return mergedAtStart;
  }

  public void setMergedAtStart(String mergedAtStart) {
    this.mergedAtStart = mergedAtStart;
  }

  public String getMergedAtEnd() {
    return mergedAtEnd;
  }

  public void setMergedAtEnd(String mergedAtEnd) {
    this.mergedAtEnd = mergedAtEnd;
  }

  public String getKeyword() {
    return keyword;
  }

  public void setKeyword(String keyword) {
    this.keyword = keyword;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getRequestType() {
    return requestType;
  }

  public void setRequestType(String requestType) {
    this.requestType = requestType;
  }

  public String getTargetBranch() {
    return targetBranch;
  }

  public void setTargetBranch(String targetBranch) {
    this.targetBranch = targetBranch;
  }

  public String getMergedBy() {
    return mergedBy;
  }

  public void setMergedBy(String mergedBy) {
    this.mergedBy = mergedBy;
  }

  public String getModuleName() {
    return moduleName;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public String getIllegalType() {
    return illegalType;
  }

  public void setIllegalType(String illegalType) {
    this.illegalType = illegalType;
  }

  public String getMergeRequestIid() {
    return mergeRequestIid;
  }

  public void setMergeRequestIid(String mergeRequestIid) {
    this.mergeRequestIid = mergeRequestIid;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public CodeReviewRuleConfig getRuleConfig() {
    return ruleConfig;
  }

  public void setRuleConfig(CodeReviewRuleConfig ruleConfig) {
    this.ruleConfig = ruleConfig;
  }
}
