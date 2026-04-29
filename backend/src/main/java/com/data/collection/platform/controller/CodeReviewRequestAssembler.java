package com.data.collection.platform.controller;

import com.data.collection.platform.entity.CodeReviewRulePreviewRequest;
import com.data.collection.platform.service.CodeReviewIllegalRecordQueryRequest;
import com.data.collection.platform.service.CodeReviewIllegalRecordFilterOptionsRequest;
import com.data.collection.platform.service.CodeReviewMultiBoardOverviewRequest;
import org.springframework.stereotype.Component;

@Component
public class CodeReviewRequestAssembler {

  public CodeReviewIllegalRecordQueryRequest toIllegalRecordQueryRequest(
      CodeReviewIllegalRecordListWebRequest request) {
    return new CodeReviewIllegalRecordQueryRequest(
        request.getProjectId(),
        request.getRepositoryName(),
        request.getMergedAtStart(),
        request.getMergedAtEnd(),
        request.getKeyword(),
        request.getProjectName(),
        request.getRequestType(),
        request.getTargetBranch(),
        request.getMergedBy(),
        request.getModuleName(),
        request.getIllegalType(),
        request.getMergeRequestIid(),
        request.getOwner(),
        request.getFilterGroup(),
        request.getPage(),
        request.getSize(),
        request.getSortBy(),
        request.getSortOrder(),
        request.getRuleConfig());
  }

  public CodeReviewMultiBoardOverviewRequest toMultiBoardOverviewRequest(
      CodeReviewMultiBoardOverviewWebRequest request) {
    return new CodeReviewMultiBoardOverviewRequest(request.getSource());
  }

  public CodeReviewIllegalRecordFilterOptionsRequest toIllegalRecordFilterOptionsRequest(
      CodeReviewIllegalRecordFilterOptionsWebRequest request) {
    return new CodeReviewIllegalRecordFilterOptionsRequest(request.getProjectId());
  }

  public CodeReviewRulePreviewRequest toRulePreviewRequest(CodeReviewRulePreviewWebRequest request) {
    return new CodeReviewRulePreviewRequest(
        request.getProjectId(),
        request.getRepositoryName(),
        request.getMergedAtStart(),
        request.getMergedAtEnd(),
        request.getKeyword(),
        request.getProjectName(),
        request.getRequestType(),
        request.getTargetBranch(),
        request.getMergedBy(),
        request.getModuleName(),
        request.getIllegalType(),
        request.getMergeRequestIid(),
        request.getOwner(),
        request.getRuleConfig());
  }
}
