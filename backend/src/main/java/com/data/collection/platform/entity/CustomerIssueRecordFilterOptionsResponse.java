package com.data.collection.platform.entity;

import java.util.List;

public record CustomerIssueRecordFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> functionNames,
    List<OptionItemResponse> reasonCategories,
    List<OptionItemResponse> severityLevels,
    List<OptionItemResponse> priorityLevels,
    List<OptionItemResponse> issueStates,
    List<OptionItemResponse> bugStatuses,
    List<OptionItemResponse> categories,
    List<OptionItemResponse> assigneeNames,
    List<OptionItemResponse> milestoneTitles) {}
