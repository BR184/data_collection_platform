package com.data.collection.platform.controller;

import com.data.collection.platform.service.CodeReviewIllegalRecordQueryRequest;
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
}
