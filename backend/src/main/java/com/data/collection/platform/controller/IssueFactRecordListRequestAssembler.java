package com.data.collection.platform.controller;

import com.data.collection.platform.service.IssueFactRecordListRequest;
import org.springframework.stereotype.Component;

@Component
public class IssueFactRecordListRequestAssembler {
  public IssueFactRecordListRequest toServiceRequest(IssueFactRecordListWebRequest request) {
    return IssueFactRecordListRequest.withTagSelections(
        request.getProjectId(),
        request.getKeyword(),
        request.getSearchType(),
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
        TagSelectionRequestParser.parse(request.getTagSelections()),
        request.getPage(),
        request.getSize(),
        request.getSortBy(),
        request.getSortOrder());
  }
}
