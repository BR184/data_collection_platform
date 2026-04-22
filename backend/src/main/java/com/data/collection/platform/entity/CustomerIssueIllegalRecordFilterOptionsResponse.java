package com.data.collection.platform.entity;

import java.util.List;

public record CustomerIssueIllegalRecordFilterOptionsResponse(
    List<OptionItemResponse> projectNames,
    List<OptionItemResponse> moduleNames,
    List<OptionItemResponse> illegalReasons,
    List<OptionItemResponse> severityLevels,
    List<OptionItemResponse> priorityLevels,
    List<OptionItemResponse> issueStates,
    List<OptionItemResponse> bugStatuses,
    List<OptionItemResponse> categories,
    List<OptionItemResponse> milestoneTitles) {}
