package com.data.collection.platform.entity;

import java.util.List;

public record SystemTestIssueSearchFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> functionNames,
    List<OptionItemResponse> testingPhases,
    List<OptionItemResponse> authorNames,
    List<OptionItemResponse> assigneeNames,
    List<OptionItemResponse> issueStates,
    List<OptionItemResponse> severityLevels,
    List<OptionItemResponse> bugStatuses,
    List<OptionItemResponse> categories,
    List<OptionItemResponse> milestoneTitles) {}
