package com.data.collection.platform.controller;

public class ReviewDataRecordListRequest {
  private String keyword;
  private String title;
  private String projectName;
  private String moduleName;
  private String reviewOwner;
  private String reviewType;
  private String problemStatus;
  private String reviewExpert;
  private String filterGroup;
  private int page = 1;
  private int size = 20;
  private String sortBy;
  private String sortOrder;

  public String getKeyword() {
    return keyword;
  }

  public void setKeyword(String keyword) {
    this.keyword = keyword;
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

  public String getReviewOwner() {
    return reviewOwner;
  }

  public void setReviewOwner(String reviewOwner) {
    this.reviewOwner = reviewOwner;
  }

  public String getReviewType() {
    return reviewType;
  }

  public void setReviewType(String reviewType) {
    this.reviewType = reviewType;
  }

  public String getProblemStatus() {
    return problemStatus;
  }

  public void setProblemStatus(String problemStatus) {
    this.problemStatus = problemStatus;
  }

  public String getReviewExpert() {
    return reviewExpert;
  }

  public void setReviewExpert(String reviewExpert) {
    this.reviewExpert = reviewExpert;
  }

  public String getFilterGroup() {
    return filterGroup;
  }

  public void setFilterGroup(String filterGroup) {
    this.filterGroup = filterGroup;
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
