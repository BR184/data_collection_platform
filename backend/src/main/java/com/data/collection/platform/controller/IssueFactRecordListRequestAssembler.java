package com.data.collection.platform.controller;

import com.data.collection.platform.service.IssueFactRecordListRequest;
import org.springframework.stereotype.Component;

@Component
public class IssueFactRecordListRequestAssembler {

  public IssueFactRecordListRequest toServiceRequest(IssueFactRecordListWebRequest request) {
    return new IssueFactRecordListRequest(
        request.getProjectId(),
        request.getKeyword(),
        request.getIssueIid(),
        request.getTitle(),
        request.getProjectName(),
        request.getModuleName(),
        request.getSeverityLevel(),
        request.getPriorityLevel(),
        request.getIssueState(),
        request.getBugStatus(),
        request.getCategory(),
        request.getMilestoneTitle(),
        request.getCreatedAtStart(),
        request.getCreatedAtEnd(),
        request.getUpdatedAtStart(),
        request.getUpdatedAtEnd(),
        request.getSourceInstance(),
        request.getPage(),
        request.getSize(),
        request.getSortBy(),
        request.getSortOrder());
  }
}
